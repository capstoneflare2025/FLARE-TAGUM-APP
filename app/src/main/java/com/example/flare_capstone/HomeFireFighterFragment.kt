package com.example.flare_capstone

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

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
    private val incidentMarkers = mutableMapOf<String, Marker>() // key -> marker

    // Current selection
    private var selectedIncidentKey: String? = null
    private var currentReportPoint: LatLng? = null
    private var lastMyPoint: LatLng? = null

    // Stable numbering: key -> number; resets only when incidents become empty
    private val numberMap = mutableMapOf<String, Int>()
    private var nextNumber = 1

    // All incidents in memory
    private data class Incident(
        val key: String,         // Source/id composite
        val id: String,
        val source: Source,
        val latLng: LatLng,
        val status: String,
        val timestamp: Long      // epoch millis
    )
    private val incidents = mutableMapOf<String, Incident>() // key -> incident

    // Route polylines
    private data class OsrmRoute(
        val points: List<LatLng>,
        val durationSec: Long,
        val distanceMeters: Long
    )
    private data class DrawnRoute(
        val polyline: Polyline,
        val route: OsrmRoute,
        var isPrimary: Boolean,
        val isShortest: Boolean  // fixed label regardless of selection
    )
    private val drawnRoutes = mutableListOf<DrawnRoute>()

    // Station + DB paths
    private var stationPrefix: String? = null
    private var fireReportPath: String? = null
    private var otherEmergencyPath: String? = null
    private var smsReportPath: String? = null

    // Listeners
    private var fireListener: ValueEventListener? = null
    private var otherListener: ValueEventListener? = null
    private var smsListener: ValueEventListener? = null
    private var fireQuery: Query? = null
    private var otherQuery: Query? = null
    private var smsQuery: Query? = null

    // Cached snapshots for merge
    private var fireSnap: DataSnapshot? = null
    private var otherSnap: DataSnapshot? = null
    private var smsSnap: DataSnapshot? = null

    // Camera recenter thresholds
    private var lastCameraMy: LatLng? = null
    private var lastCameraIncident: LatLng? = null
    private val recenterMeters = 25f

    // Routing throttle
    private val bg = Executors.newSingleThreadExecutor()
    private var lastRoutedOrigin: LatLng? = null
    private var lastRoutedDest: LatLng? = null
    private val routeRecomputeMeters = 25f

    private enum class Source { FIRE, OTHER, SMS }

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
        smsReportPath      = "$base/${stationPrefix}SmsReport"   // e.g. MabiniFireStation/MabiniSmsReport

        binding.completed.setOnClickListener { markCompleted() }

        attachReportListeners()
        ensureLocationPermission()
    }

    // ---------- Helpers: time parsing ----------

    private fun getEpochFromDateTime(node: DataSnapshot): Long? {
        val dateStr = node.child("date").getValue(String::class.java)?.trim()
        val timeStr = node.child("time").getValue(String::class.java)?.trim()
        if (dateStr.isNullOrEmpty() || timeStr.isNullOrEmpty()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            fmt.timeZone = TimeZone.getDefault()
            fmt.parse("$dateStr $timeStr")?.time
        } catch (_: Exception) { null }
    }

    // Normalize any seconds→milliseconds and support several fields.
    private fun readTimestampMillis(node: DataSnapshot): Long? {
        val raw = getLongRelaxed(node, "acceptedAt")
            ?: getLongRelaxed(node, "timeStamp")
            ?: getLongRelaxed(node, "timestamp")
            ?: getLongRelaxed(node, "time")
            ?: getEpochFromDateTime(node)
            ?: return null
        val ms = if (raw in 1..9_999_999_999L) raw * 1000 else raw
        return if (ms > 0) ms else null
    }

    // ---------- Lifecycle cleanup ----------

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

        // Tap on a route polyline
        gMap?.setOnPolylineClickListener { tapped ->
            val dr = drawnRoutes.find { it.polyline == tapped } ?: return@setOnPolylineClickListener

            val mins = max(1, (dr.route.durationSec / 60).toInt())
            val km = (dr.route.distanceMeters / 100.0).roundToInt() / 10.0

            // Promote the tapped route visually
            highlightRoute(dr)

            // fixed label by precomputed shortest flag
            val label = if (dr.isShortest) "Shortest route" else "Alternative route"

            // Update selected info + marker info window
            selectedIncidentKey?.let { key ->
                incidentMarkers[key]?.let { mk ->
                    mk.snippet = "${mins}m • ${km}km"
                    mk.showInfoWindow()
                }
                updateSelectedInfo(etaMins = mins, distKm = km)
            }

            Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show()
        }

        // Tap on an incident marker: select & route to it
        gMap?.setOnMarkerClickListener { marker ->
            if (marker == myMarker) return@setOnMarkerClickListener false
            val key = marker.tag as? String ?: return@setOnMarkerClickListener false
            selectIncident(key, animateCamera = true)
            marker.showInfoWindow()
            true
        }

        updatePins(lastMyPoint, currentReportPoint)
    }

    private fun updatePins(myLoc: LatLng?, reportLoc: LatLng?) {
        val map = gMap ?: return

        if (myLoc != null) {
            lastMyPoint = myLoc
            if (myMarker == null) {
                myMarker = gMap?.addMarker(
                    MarkerOptions()
                        .position(myLoc)
                        .title("You")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            } else {
                myMarker?.position = myLoc
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

    // ---------- Firebase listeners (merge FireReport + OtherEmergency + SmsReports) ----------

    private fun attachReportListeners() {
        val firePath  = fireReportPath ?: return
        val otherPath = otherEmergencyPath ?: return
        val smsPath   = smsReportPath ?: return

        fireQuery = FirebaseDatabase.getInstance().getReference(firePath)
            .orderByChild("status")
            .equalTo("Ongoing")
            .limitToLast(50)

        otherQuery = FirebaseDatabase.getInstance().getReference(otherPath)
            .orderByChild("status")
            .equalTo("Ongoing")
            .limitToLast(50)

        // SMS: only Ongoing
        smsQuery = FirebaseDatabase.getInstance().getReference(smsPath)
            .orderByChild("status")
            .equalTo("Ongoing")
            .limitToLast(50)

        fireListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) { fireSnap = snap; rebuildIncidents() }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "FireReport cancelled: ${error.message}") }
        }
        otherListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) { otherSnap = snap; rebuildIncidents() }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "OtherEmergency cancelled: ${error.message}") }
        }
        smsListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) { smsSnap = snap; rebuildIncidents() }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "SmsReports cancelled: ${error.message}") }
        }

        fireQuery?.addValueEventListener(fireListener as ValueEventListener)
        otherQuery?.addValueEventListener(otherListener as ValueEventListener)
        smsQuery?.addValueEventListener(smsListener as ValueEventListener)
    }

    private fun detachReportListeners() {
        fireListener?.let { fireQuery?.removeEventListener(it) }
        otherListener?.let { otherQuery?.removeEventListener(it) }
        smsListener?.let  { smsQuery?.removeEventListener(it) }
        fireListener = null
        otherListener = null
        smsListener = null
        fireQuery = null
        otherQuery = null
        smsQuery = null
        fireSnap = null
        otherSnap = null
        smsSnap = null
    }

    // ---------- Incidents → markers + selection ----------

    private fun rebuildIncidents() {
        val fresh = mutableMapOf<String, Incident>()

        fun addFromSnap(snap: DataSnapshot?, source: Source) {
            snap?.children?.forEach { c ->
                val status = (c.child("status").value as? String)?.trim()?.lowercase() ?: ""
                if (status != "ongoing") return@forEach

                val lat = getDoubleRelaxed(c, "latitude") ?: getDoubleRelaxed(c, "lat")
                val lon = getDoubleRelaxed(c, "longitude") ?: getDoubleRelaxed(c, "lng")
                val ts  = readTimestampMillis(c) ?: return@forEach

                if (lat == null || lon == null) return@forEach
                val id = c.key ?: return@forEach
                val key = "${source.name}/$id"
                fresh[key] = Incident(
                    key = key,
                    id = id,
                    source = source,
                    latLng = LatLng(lat, lon),
                    status = "Ongoing",
                    timestamp = ts
                )
            }
        }

        addFromSnap(fireSnap, Source.FIRE)
        addFromSnap(otherSnap, Source.OTHER)
        addFromSnap(smsSnap, Source.SMS)

        // Replace in-memory incidents
        incidents.clear()
        incidents.putAll(fresh)

        // Assign stable numbers to new incidents only
        if (incidents.isEmpty()) {
            // reset numbering ONLY when list becomes empty
            numberMap.clear()
            nextNumber = 1
            selectedIncidentKey = null
            currentReportPoint = null
            clearAllRoutes()
            updateSelectedInfo() // will show "No active incidents"
            // also remove any leftover markers
            incidentMarkers.values.forEach { it.remove() }
            incidentMarkers.clear()
            return
        } else {
            // add numbers for any new keys (ordered by timestamp asc just for consistency)
            incidents.keys.sortedBy { incidents[it]!!.timestamp }.forEach { key ->
                if (!numberMap.containsKey(key)) {
                    numberMap[key] = nextNumber++
                }
            }
        }

        updateIncidentMarkers()
        ensureSelection()
    }

    private fun updateIncidentMarkers() {
        val map = gMap ?: return

        // Remove markers no longer present
        val keysToRemove = incidentMarkers.keys - incidents.keys
        keysToRemove.forEach { k ->
            incidentMarkers[k]?.remove()
            incidentMarkers.remove(k)
        }

        // Add / update markers using stable numbers
        incidents.values.forEach { inc ->
            val number = numberMap[inc.key] ?: 0
            val title = when (inc.source) {
                Source.FIRE  -> "[FIRE #$number]"
                Source.OTHER -> "[OTHER #$number]"
                Source.SMS   -> "[SMS #$number]"
            }

            val iconRes = when (inc.source) {
                Source.FIRE  -> R.drawable.ic_pin_fire
                Source.OTHER -> R.drawable.ic_pin_other
                Source.SMS   -> R.drawable.ic_pin_sms
            }
            val icon = bitmapFromVector(iconRes)

            val existing = incidentMarkers[inc.key]
            if (existing == null) {
                val mk = map.addMarker(
                    MarkerOptions()
                        .position(inc.latLng)
                        .title(title)
                        .snippet("Status: ${inc.status}")
                        .icon(icon)
                )
                mk?.tag = inc.key
                if (mk != null) incidentMarkers[inc.key] = mk
            } else {
                existing.position = inc.latLng
                existing.title = title
                existing.setIcon(icon)
                if (existing.tag == null) existing.tag = inc.key
            }
        }
    }

    private fun ensureSelection() {
        // keep current selection if it still exists
        selectedIncidentKey?.let {
            if (incidents.containsKey(it)) {
                val sel = incidents[it]!!
                selectIncident(sel.key, animateCamera = false)
                return
            }
        }
        // otherwise pick the incident with the LOWEST assigned number
        val next = incidents.values.minByOrNull { numberMap[it.key] ?: Int.MAX_VALUE } ?: return
        selectIncident(next.key, animateCamera = true)
    }

    private fun selectIncident(key: String, animateCamera: Boolean) {
        val inc = incidents[key] ?: return
        selectedIncidentKey = key
        currentReportPoint = inc.latLng

        // Update status line and marker
        incidentMarkers[key]?.snippet = "Status: ${inc.status}"
        updateSelectedInfo()

        if (animateCamera) {
            try {
                gMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(inc.latLng, 15f))
            } catch (_: Exception) {}
        }

        // trigger routing
        updatePins(lastMyPoint, currentReportPoint)
    }

    // ---------- Firebase value helpers ----------

    private fun getDoubleRelaxed(node: DataSnapshot, key: String): Double? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun getLongRelaxed(node: DataSnapshot, key: String): Long? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    // ---------- Complete ----------

    private fun markCompleted() {
        val key = selectedIncidentKey ?: return
        val inc = incidents[key] ?: return

        val path = when (inc.source) {
            Source.FIRE  -> fireReportPath
            Source.OTHER -> otherEmergencyPath
            Source.SMS   -> smsReportPath
        } ?: return

        FirebaseDatabase.getInstance()
            .getReference("$path/${inc.id}")
            .child("status")
            .setValue("Completed")

        // Local cleanup will happen on the next onDataChange
        Log.d(TAG, "Marked completed id=${inc.id} src=${inc.source}")
        Toast.makeText(requireContext(), "Marked as Completed", Toast.LENGTH_SHORT).show()
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

                // Sort by distance ascending (or duration if you prefer fastest)
                val sorted = all.sortedBy { it.distanceMeters }

                sorted.forEachIndexed { index, r ->
                    val isPrimary = index == 0
                    val polyOpts = PolylineOptions()
                        .addAll(r.points)
                        .width(if (isPrimary) 12f else 8f)
                        .color(if (isPrimary) 0xFF2962FF.toInt() else 0x802962FF.toInt())
                        .zIndex(if (isPrimary) 2f else 1f)
                        .clickable(true)

                    if (!isPrimary) {
                        polyOpts.pattern(listOf(Dot(), Gap(14f))) // dashed for alternatives
                    }

                    val pl = map.addPolyline(polyOpts)
                    drawnRoutes += DrawnRoute(
                        polyline = pl,
                        route = r,
                        isPrimary = isPrimary,
                        isShortest = index == 0
                    )
                }

                // Update info on selected marker + status text with primary route stats
                val chosen = drawnRoutes.firstOrNull { it.isPrimary }?.route
                if (chosen != null) {
                    val mins = max(1, (chosen.durationSec / 60).toInt())
                    val km = (chosen.distanceMeters / 100.0).roundToInt() / 10.0
                    selectedIncidentKey?.let { key ->
                        incidentMarkers[key]?.let { mk ->
                            mk.snippet = "${mins}m • ${km}km"
                            mk.showInfoWindow()
                        }
                    }
                    updateSelectedInfo(etaMins = mins, distKm = km)
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
        drawnRoutes.forEach {
            if (it.isPrimary) {
                it.isPrimary = false
                it.polyline.width = 8f
                it.polyline.color = 0x802962FF.toInt()
                it.polyline.pattern = listOf(Dot(), Gap(14f))
                it.polyline.zIndex = 1f
            }
        }
        target.isPrimary = true
        target.polyline.width = 12f
        target.polyline.color = 0xFF2962FF.toInt()
        target.polyline.pattern = null
        target.polyline.zIndex = 2f

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

    // Polyline precision 5 (OSRM default) -> List<LatLng>
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

    // ---------- UI helpers ----------

    private fun sourceLabel(src: Source): String = when (src) {
        Source.FIRE  -> "FIRE"
        Source.OTHER -> "OTHER"
        Source.SMS   -> "SMS"
    }

    private fun updateSelectedInfo(etaMins: Int? = null, distKm: Double? = null) {
        val key = selectedIncidentKey
        if (key == null || !incidents.containsKey(key)) {
            binding.selectedInfo.text = "No active incidents"
            return
        }
        val inc = incidents[key]!!
        val no = numberMap[key] ?: 0
        val base = "${sourceLabel(inc.source)} #$no • ${inc.status}"
        binding.selectedInfo.text = if (etaMins != null && distKm != null) {
            "$base • ${etaMins}m • ${distKm}km"
        } else base
    }

    // ---------- Bitmap helpers ----------

    private fun pinIcon(@DrawableRes resId: Int, colorHex: String): BitmapDescriptor {
        val ctx = requireContext()
        val base = ContextCompat.getDrawable(ctx, resId)!!.mutate()
        val wrapped = DrawableCompat.wrap(base)
        DrawableCompat.setTint(wrapped, Color.parseColor(colorHex))
        val bmp = Bitmap.createBitmap(
            wrapped.intrinsicWidth.coerceAtLeast(48),
            wrapped.intrinsicHeight.coerceAtLeast(48),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        wrapped.setBounds(0, 0, canvas.width, canvas.height)
        wrapped.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun bitmapFromVector(@DrawableRes id: Int): BitmapDescriptor {
        val d = ContextCompat.getDrawable(requireContext(), id)!!.mutate()
        val bmp = Bitmap.createBitmap(
            d.intrinsicWidth.coerceAtLeast(48),
            d.intrinsicHeight.coerceAtLeast(48),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }
}
