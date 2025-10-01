package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.flare_capstone.databinding.FragmentHomeFireFighterBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFireFighterFragment : Fragment(), OnMapReadyCallback {

    private val TAG = "HomeFF"

    private var _binding: FragmentHomeFireFighterBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocation: FusedLocationProviderClient

    // Google Maps
    private var gMap: GoogleMap? = null

    // Pins
    private var myMarker: Marker? = null
    private var incidentMarker: Marker? = null

    // Route polylines
    private data class OsrmRoute(
        val points: List<LatLng>,
        val durationSec: Long,
        val distanceMeters: Long
    )
    private data class DrawnRoute(
        val polyline: Polyline,
        val route: OsrmRoute,
        var isPrimary: Boolean
    )
    private val drawnRoutes = mutableListOf<DrawnRoute>()

    // Station + DB paths
    private var stationPrefix: String? = null
    private var fireReportPath: String? = null
    private var otherEmergencyPath: String? = null

    // Current pick
    private var ongoingIncidentId: String? = null
    private var ongoingIncidentSource: Source? = null
    private var currentReportPoint: LatLng? = null
    private var lastMyPoint: LatLng? = null

    // Listeners
    private var fireListener: ValueEventListener? = null
    private var otherListener: ValueEventListener? = null
    private var fireQuery: Query? = null
    private var otherQuery: Query? = null

    // Cached snapshots for merge
    private var fireSnap: DataSnapshot? = null
    private var otherSnap: DataSnapshot? = null

    // Camera recenter thresholds
    private var lastCameraMy: LatLng? = null
    private var lastCameraIncident: LatLng? = null
    private val recenterMeters = 25f

    // Routing throttle
    private val bg = Executors.newSingleThreadExecutor()
    private var lastRoutedOrigin: LatLng? = null
    private var lastRoutedDest: LatLng? = null
    private val routeRecomputeMeters = 25f

    private enum class Source { FIRE, OTHER }

    private val locationPerms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grant ->
        val ok = (grant[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (grant[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (ok) startLocationUpdates() else Log.w(TAG, "Location permission denied; navigation limited")
        enableMyLocationUiIfPermitted()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeFireFighterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        auth = FirebaseAuth.getInstance()
        fusedLocation = LocationServices.getFusedLocationProviderClient(requireContext())

        // Attach a SupportMapFragment into mapContainer
        val existing = childFragmentManager.findFragmentById(binding.mapContainer.id) as? SupportMapFragment
        val mapFragment = existing ?: SupportMapFragment.newInstance().also {
            childFragmentManager.beginTransaction()
                .replace(binding.mapContainer.id, it)
                .commit()
            childFragmentManager.executePendingTransactions()
        }
        mapFragment.getMapAsync(this)

        // Station select from email
        stationPrefix = when (auth.currentUser?.email?.lowercase()) {
            "mabinifirefighter123@gmail.com"     -> "Mabini"
            "lafilipinafirefighter123@gmail.com" -> "LaFilipina"
            "canocotanfirefighter123@gmail.com"  -> "Canocotan"
            else -> null
        }
        if (stationPrefix == null) {
            Log.w(TAG, "Unknown firefighter email; abort")
            return
        }

        val base = "${stationPrefix}FireStation"
        fireReportPath     = "$base/${stationPrefix}FireReport"
        otherEmergencyPath = "$base/${stationPrefix}OtherEmergency"

        binding.completed.setOnClickListener { markCompleted() }

        attachReportListeners()
        ensureLocationPermission()
    }

    override fun onDestroyView() {
        detachReportListeners()
        stopLocationUpdates()
        _binding = null
        super.onDestroyView()
    }

    // ---------- Permissions / MyLocation ----------

    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            startLocationUpdates()
            enableMyLocationUiIfPermitted()
        } else {
            reqPerms.launch(locationPerms)
        }
    }

    private fun enableMyLocationUiIfPermitted() {
        try {
            if (hasLocationPermission()) {
                gMap?.isMyLocationEnabled = true
                gMap?.uiSettings?.isMyLocationButtonEnabled = true
            }
        } catch (_: SecurityException) { /* ignore */ }
    }

    // ---------- Map + Markers ----------

    override fun onMapReady(map: GoogleMap) {
        gMap = map
        gMap?.uiSettings?.isZoomControlsEnabled = true
        gMap?.uiSettings?.isCompassEnabled = true
        gMap?.isTrafficEnabled = true
        gMap?.moveCamera(CameraUpdateFactory.zoomTo(15f))
        enableMyLocationUiIfPermitted()

        // Let users tap any polyline to highlight/select that route
        gMap?.setOnPolylineClickListener { tapped ->
            val dr = drawnRoutes.find { it.polyline == tapped } ?: return@setOnPolylineClickListener
            highlightRoute(dr)
            val mins = max(1, (dr.route.durationSec / 60).toInt())
            val km = (dr.route.distanceMeters / 100.0).roundToInt() / 10.0
            incidentMarker?.snippet = "${mins}m • ${km}km"
            incidentMarker?.showInfoWindow()
            Toast.makeText(requireContext(), if (dr.isPrimary) "Shortest route" else "Alternative route", Toast.LENGTH_SHORT).show()
        }

        updatePins(lastMyPoint, currentReportPoint)
    }

    private fun updatePins(myLoc: LatLng?, reportLoc: LatLng?) {
        val map = gMap ?: return

        if (myLoc != null) {
            lastMyPoint = myLoc
            if (myMarker == null) {
                myMarker = map.addMarker(
                    MarkerOptions()
                        .position(myLoc)
                        .title("You")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            } else {
                myMarker?.position = myLoc
            }
        }

        if (reportLoc != null) {
            if (incidentMarker == null) {
                incidentMarker = map.addMarker(
                    MarkerOptions()
                        .position(reportLoc)
                        .title("Incident")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            } else {
                incidentMarker?.position = reportLoc
            }
        }

        // Camera (recenter if moved)
        val needRecenter =
            (lastCameraMy == null || (myLoc != null && distanceMeters(lastCameraMy!!, myLoc) > recenterMeters)) ||
                    (lastCameraIncident == null || (reportLoc != null && distanceMeters(lastCameraIncident!!, reportLoc) > recenterMeters))

        if (needRecenter) {
            when {
                myLoc != null && reportLoc != null -> {
                    val bounds = LatLngBounds.builder().include(myLoc).include(reportLoc).build()
                    try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                    catch (_: Exception) { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                    lastCameraMy = myLoc
                    lastCameraIncident = reportLoc
                }
                myLoc != null -> {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(myLoc, 15f))
                    lastCameraMy = myLoc
                }
                reportLoc != null -> {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(reportLoc, 15f))
                    lastCameraIncident = reportLoc
                }
            }
        }

        // Trigger routing if both points exist and moved enough
        val origin = lastMyPoint
        val dest = currentReportPoint
        if (origin != null && dest != null && shouldRecomputeRoutes(origin, dest)) {
            fetchAndDrawOsrmRoutes(origin, dest)
        }
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Float {
        val res = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        return res[0]
    }

    // Live location
    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val last: Location = result.lastLocation ?: return
            updatePins(LatLng(last.latitude, last.longitude), currentReportPoint)
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        try {
            fusedLocation.requestLocationUpdates(req, locCallback, requireActivity().mainLooper)
        } catch (_: SecurityException) {
            Log.w(TAG, "requestLocationUpdates SecurityException")
        }
    }

    private fun stopLocationUpdates() {
        try { fusedLocation.removeLocationUpdates(locCallback) } catch (_: Exception) {}
    }

    // ---------- Firebase listeners (merge FireReport + OtherEmergency) ----------

    private fun attachReportListeners() {
        val firePath = fireReportPath ?: return
        val otherPath = otherEmergencyPath ?: return

        fireQuery = FirebaseDatabase.getInstance().getReference(firePath)
            .orderByChild("status")
            .equalTo("Ongoing")
            .limitToLast(20)

        otherQuery = FirebaseDatabase.getInstance().getReference(otherPath)
            .orderByChild("status")
            .equalTo("Ongoing")
            .limitToLast(20)

        fireListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                fireSnap = snap
                recomputePick()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "FireReport cancelled: ${error.message}")
            }
        }
        otherListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                otherSnap = snap
                recomputePick()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "OtherEmergency cancelled: ${error.message}")
            }
        }

        fireQuery?.addValueEventListener(fireListener as ValueEventListener)
        otherQuery?.addValueEventListener(otherListener as ValueEventListener)
    }

    private fun detachReportListeners() {
        fireListener?.let { fireQuery?.removeEventListener(it) }
        otherListener?.let { otherQuery?.removeEventListener(it) }
        fireListener = null
        otherListener = null
        fireQuery = null
        otherQuery = null
        fireSnap = null
        otherSnap = null
    }

    private fun recomputePick() {
        var newestTs = Long.MIN_VALUE
        var pickId: String? = null
        var pickPt: LatLng? = null
        var pickSrc: Source? = null

        var total = 0
        var ongoingCount = 0

        // FireReport: "timeStamp"
        fireSnap?.let { snap ->
            for (c in snap.children) {
                total++
                val lat = getDoubleRelaxed(c, "latitude")
                val lon = getDoubleRelaxed(c, "longitude")
                val ts = c.child("timeStamp").getValue(Long::class.java) ?: 0L
                if (lat != null && lon != null && ts > 0) {
                    ongoingCount++
                    if (ts > newestTs) {
                        newestTs = ts
                        pickId = c.key
                        pickPt = LatLng(lat, lon)
                        pickSrc = Source.FIRE
                    }
                }
            }
        }

        // OtherEmergency: "timestamp"
        otherSnap?.let { snap ->
            for (c in snap.children) {
                total++
                val lat = getDoubleRelaxed(c, "latitude")
                val lon = getDoubleRelaxed(c, "longitude")
                val ts = c.child("timestamp").getValue(Long::class.java) ?: 0L
                if (lat != null && lon != null && ts > 0) {
                    ongoingCount++
                    if (ts > newestTs) {
                        newestTs = ts
                        pickId = c.key
                        pickPt = LatLng(lat, lon)
                        pickSrc = Source.OTHER
                    }
                }
            }
        }

        if (pickId == null || pickPt == null || pickSrc == null) {
            ongoingIncidentId = null
            ongoingIncidentSource = null
            currentReportPoint = null
            clearAllRoutes()
            incidentMarker?.let { it.remove(); incidentMarker = null }
            updatePins(lastMyPoint, null)
            Log.d(TAG, "No ongoing reports; total=$total ongoingCount=$ongoingCount")
            return
        }

        ongoingIncidentId = pickId
        ongoingIncidentSource = pickSrc
        currentReportPoint = pickPt
        Log.d(TAG, "Picked ongoing ${pickSrc.name} id=$pickId ts=$newestTs at $pickPt")
        updatePins(lastMyPoint, currentReportPoint)
    }

    private fun getDoubleRelaxed(node: DataSnapshot, key: String): Double? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun markCompleted() {
        val id = ongoingIncidentId ?: return
        val src = ongoingIncidentSource ?: return
        val path = when (src) {
            Source.FIRE -> fireReportPath
            Source.OTHER -> otherEmergencyPath
        } ?: return
        FirebaseDatabase.getInstance()
            .getReference("$path/$id")
            .child("status")
            .setValue("Completed")
        clearAllRoutes()
        incidentMarker?.let { it.remove(); incidentMarker = null }
        Log.d(TAG, "Marked completed $id at $src")
    }

    // ---------- OSRM routing (show multiple alternatives) ----------

    private fun shouldRecomputeRoutes(origin: LatLng, dest: LatLng): Boolean {
        val prevO = lastRoutedOrigin
        val prevD = lastRoutedDest
        if (prevO == null || prevD == null) {
            lastRoutedOrigin = origin
            lastRoutedDest = dest
            return true
        }
        val movedO = distanceMeters(prevO, origin)
        val movedD = distanceMeters(prevD, dest)
        return if (movedO > routeRecomputeMeters || movedD > routeRecomputeMeters) {
            lastRoutedOrigin = origin
            lastRoutedDest = dest
            true
        } else false
    }

    private fun fetchAndDrawOsrmRoutes(origin: LatLng, dest: LatLng) {
        bg.execute {
            val all = fetchOsrmRoutes(origin, dest)
            requireActivity().runOnUiThread {
                val map = gMap ?: return@runOnUiThread
                clearAllRoutes()

                if (all.isEmpty()) {
                    Toast.makeText(requireContext(), "No route found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                // Sort by distance ascending (use durationSec for fastest)
                val sorted = all.sortedBy { it.distanceMeters }

                // Draw the shortest first as primary, others as alternatives
                sorted.forEachIndexed { index, r ->
                    val isPrimary = index == 0
                    val polyOpts = PolylineOptions()
                        .addAll(r.points)
                        .width(if (isPrimary) 12f else 8f)
                        .color(if (isPrimary) 0xFF2962FF.toInt() else 0x802962FF.toInt())
                        .zIndex(if (isPrimary) 2f else 1f)
                        .clickable(true)

                    if (!isPrimary) {
                        polyOpts.pattern(listOf(Dot(), Gap(14f))) // dashed for alts
                    }

                    val pl = map.addPolyline(polyOpts)
                    drawnRoutes += DrawnRoute(polyline = pl, route = r, isPrimary = isPrimary)
                }

                // Update marker info with primary route stats
                val chosen = drawnRoutes.firstOrNull { it.isPrimary }?.route
                if (chosen != null) {
                    val mins = max(1, (chosen.durationSec / 60).toInt())
                    val km = (chosen.distanceMeters / 100.0).roundToInt() / 10.0
                    incidentMarker?.snippet = "${mins}m • ${km}km"
                    incidentMarker?.showInfoWindow()
                    Log.d(TAG, "OSRM routes=${sorted.size} primary mins=$mins km=$km")
                }
            }
        }
    }

    private fun fetchOsrmRoutes(origin: LatLng, dest: LatLng): List<OsrmRoute> {
        val servers = listOf(
            "https://router.project-osrm.org",
            "https://routing.openstreetmap.de/routed-car"
        )
        val extras = listOf("&exclude=ferry", "")

        for (base in servers) {
            for (extra in extras) {
                val urlStr = "$base/route/v1/driving/" +
                        "${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}" +
                        "?overview=full&geometries=polyline&steps=false&alternatives=true&continue_straight=true$extra"

                val res = runCatching { requestOsrmAll(urlStr) }.getOrNull()
                if (!res.isNullOrEmpty()) return res
            }
        }
        return emptyList()
    }

    private fun requestOsrmAll(urlStr: String): List<OsrmRoute> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
            }
            val code = conn.responseCode
            val reader = (if (code in 200..299) conn.inputStream else conn.errorStream)
            val resp = BufferedReader(InputStreamReader(reader)).use { it.readText() }
            Log.d(TAG, "OSRM code=$code body=${resp.take(160)}")
            if (code !in 200..299) return emptyList()

            val root = JSONObject(resp)
            if (root.optString("code") != "Ok") return emptyList()
            val arr = root.optJSONArray("routes") ?: JSONArray()
            if (arr.length() == 0) return emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val poly = r.optString("geometry", "")
                    val durSec = (r.optDouble("duration", 0.0)).toLong()
                    val dist = (r.optDouble("distance", 0.0)).toLong()
                    val pts = decodePolylineE5ToLatLng(poly)
                    if (pts.isNotEmpty()) add(OsrmRoute(pts, durSec, dist))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM error: ${e.message}")
            emptyList()
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun highlightRoute(target: DrawnRoute) {
        val map = gMap ?: return
        // Demote any current primary
        drawnRoutes.forEach {
            if (it.isPrimary) {
                it.isPrimary = false
                it.polyline.width = 8f
                it.polyline.color = 0x802962FF.toInt()
                it.polyline.pattern = listOf(Dot(), Gap(14f))
                it.polyline.zIndex = 1f
            }
        }
        // Promote target as primary
        target.isPrimary = true
        target.polyline.width = 12f
        target.polyline.color = 0xFF2962FF.toInt()
        target.polyline.pattern = null
        target.polyline.zIndex = 2f

        // Optionally refit camera to the selected route bounds
        try {
            val b = LatLngBounds.builder().apply {
                target.route.points.forEach { include(it) }
            }.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120))
        } catch (_: Exception) {}
    }

    private fun clearAllRoutes() {
        drawnRoutes.forEach { it.polyline.remove() }
        drawnRoutes.clear()
    }

    // Polyline precision 5 (OSRM default) -> List<LatLng> for Google Maps
    private fun decodePolylineE5ToLatLng(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val path = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            path.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return path
    }

    // ---------- Google Maps app navigation (optional fallback) ----------

    private fun openGoogleMapsDirections(origin: LatLng, dest: LatLng) {
        val uri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1" +
                    "&origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${dest.latitude},${dest.longitude}" +
                    "&travelmode=driving&dir_action=navigate"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
