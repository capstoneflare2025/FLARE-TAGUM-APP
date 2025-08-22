package com.example.flare_capstone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFireFighterFragment : Fragment() {

    private val TAG = "HomeFF"

    private var _binding: FragmentHomeFireFighterBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocation: FusedLocationProviderClient

    // OSM
    private lateinit var map: MapView

    // Pins + route
    private var myPin: Marker? = null
    private var incidentPin: Marker? = null
    private var routeLine: Polyline? = null

    // Station + DB paths
    private var stationPrefix: String? = null
    private var fireReportPath: String? = null          // e.g. MabiniFireStation/MabiniFireReport
    private var otherEmergencyPath: String? = null      // e.g. MabiniFireStation/MabiniOtherEmergency

    // Current pick
    private var ongoingIncidentId: String? = null
    private var ongoingIncidentSource: Source? = null
    private var currentReportPoint: GeoPoint? = null
    private var lastMyPoint: GeoPoint? = null

    // Listeners
    private var fireListener: ValueEventListener? = null
    private var otherListener: ValueEventListener? = null
    private var fireQuery: Query? = null
    private var otherQuery: Query? = null

    // Cached snapshots for merge
    private var fireSnap: DataSnapshot? = null
    private var otherSnap: DataSnapshot? = null

    // Routing (OSRM)
    private val bg = Executors.newSingleThreadExecutor()
    private var lastRoutedOrigin: GeoPoint? = null
    private var lastRoutedDest: GeoPoint? = null
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
        if (ok) startLocationUpdates() else Log.w(TAG, "Location permission denied; routing limited")
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

        // OSM config
        val appCtx = requireContext().applicationContext
        Configuration.getInstance().load(
            appCtx,
            appCtx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = appCtx.packageName

        // Map init
        map = binding.osmMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

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
        otherEmergencyPath = "$base/${stationPrefix}OtherEmergency" // adjust if your node name differs

        binding.completed.setOnClickListener { markCompleted() }

        attachReportListeners()
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        binding.osmMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.osmMap.onPause()
    }

    override fun onDestroyView() {
        detachReportListeners()
        stopLocationUpdates()
        routeLine?.let { map.overlayManager.remove(it) }
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
        if (hasLocationPermission()) startLocationUpdates() else reqPerms.launch(locationPerms)
    }

    // ---------- Map + Markers ----------

    private fun updatePins(myLoc: GeoPoint?, reportLoc: GeoPoint?) {
        if (myLoc != null) {
            lastMyPoint = myLoc
            if (myPin == null) {
                myPin = Marker(map).apply {
                    position = myLoc
                    title = "You"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(myPin)
            } else {
                myPin?.position = myLoc
            }
        }

        if (reportLoc != null) {
            if (incidentPin == null) {
                incidentPin = Marker(map).apply {
                    position = reportLoc
                    title = "Incident"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(incidentPin)
            } else {
                incidentPin?.position = reportLoc
            }
        }

        val pts = mutableListOf<GeoPoint>()
        myLoc?.let { pts += it }
        reportLoc?.let { pts += it }

        when (pts.size) {
            1 -> { map.controller.setZoom(15.0); map.controller.animateTo(pts.first()) }
            2 -> {
                val bb = BoundingBox.fromGeoPoints(pts)
                try { map.zoomToBoundingBox(bb, true, 120) } catch (_: Throwable) { map.zoomToBoundingBox(bb, true) }
            }
        }
        map.invalidate()

        val origin = lastMyPoint
        val dest = currentReportPoint
        if (origin != null && dest != null && shouldRecomputeRoutes(origin, dest)) {
            fetchAndDrawOsrmRoute(origin, dest)
        }
    }

    private fun shouldRecomputeRoutes(origin: GeoPoint, dest: GeoPoint): Boolean {
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

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Float {
        val res = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        return res[0]
    }

    // Live location
    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val last: Location = result.lastLocation ?: return
            updatePins(GeoPoint(last.latitude, last.longitude), currentReportPoint)
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

        // status == "Ongoing" on both nodes. Limit to reasonable number.
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
        var pickPt: GeoPoint? = null
        var pickSrc: Source? = null

        var total = 0
        var ongoingCount = 0
        val tsSamples = mutableListOf<Long>()

        // Scan FireReport: timestamp key = "timeStamp"
        fireSnap?.let { snap ->
            for (c in snap.children) {
                total++
                val lat = getDoubleRelaxed(c, "latitude")
                val lon = getDoubleRelaxed(c, "longitude")
                val ts = c.child("timeStamp").getValue(Long::class.java) ?: 0L
                if (lat != null && lon != null && ts > 0) {
                    ongoingCount++
                    tsSamples += ts
                    if (ts > newestTs) {
                        newestTs = ts
                        pickId = c.key
                        pickPt = GeoPoint(lat, lon)
                        pickSrc = Source.FIRE
                    }
                }
            }
        }

        // Scan OtherEmergency: timestamp key = "timestamp"
        otherSnap?.let { snap ->
            for (c in snap.children) {
                total++
                val lat = getDoubleRelaxed(c, "latitude")
                val lon = getDoubleRelaxed(c, "longitude")
                val ts = c.child("timestamp").getValue(Long::class.java) ?: 0L
                if (lat != null && lon != null && ts > 0) {
                    ongoingCount++
                    tsSamples += ts
                    if (ts > newestTs) {
                        newestTs = ts
                        pickId = c.key
                        pickPt = GeoPoint(lat, lon)
                        pickSrc = Source.OTHER
                    }
                }
            }
        }

        if (pickId == null || pickPt == null || pickSrc == null) {
            ongoingIncidentId = null
            ongoingIncidentSource = null
            currentReportPoint = null
            clearRouteOnly()
            incidentPin?.let { map.overlays.remove(it); incidentPin = null; map.invalidate() }
            updatePins(myPin?.position, null)
            Log.d(TAG, "No ongoing reports after scan; total=$total ongoingCount=$ongoingCount tsSamples=$tsSamples moreTs=${tsSamples.size}")
            return
        }

        ongoingIncidentId = pickId
        ongoingIncidentSource = pickSrc
        currentReportPoint = pickPt
        Log.d(TAG, "Picked ongoing ${pickSrc.name} id=$pickId ts=$newestTs at $pickPt")
        updatePins(myPin?.position, currentReportPoint)
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
        clearRouteOnly()
        incidentPin?.let { map.overlays.remove(it); incidentPin = null; map.invalidate() }
        Log.d(TAG, "Marked completed $id at $src")
    }

    private fun clearRouteOnly() {
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        lastRoutedOrigin = null
        lastRoutedDest = null
        map.invalidate()
    }

    // ---------- OSRM Routing ----------

    private data class OsrmRoute(
        val points: List<GeoPoint>,
        val durationSec: Long,
        val distanceMeters: Long
    )

    private fun fetchAndDrawOsrmRoute(origin: GeoPoint, dest: GeoPoint) {
        bg.execute {
            val res = fetchOsrmRoute(origin, dest)
            requireActivity().runOnUiThread {
                if (res == null || res.points.isEmpty()) {
                    clearRouteOnly()
                    Toast.makeText(requireContext(), "No route found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                routeLine?.let { map.overlays.remove(it) }
                routeLine = Polyline().apply {
                    outlinePaint.color = Color.parseColor("#2962FF")
                    outlinePaint.strokeWidth = 12f
                    setPoints(res.points)
                }
                map.overlays.add(routeLine)

                val mins = max(1, (res.durationSec / 60).toInt())
                val km = (res.distanceMeters / 100.0).roundToInt() / 10.0
                incidentPin?.title = "Incident • ${mins}m • ${km}km"
                incidentPin?.showInfoWindow()

                map.invalidate()
                Log.d(TAG, "OSRM route drawn: ${mins}m, ${km}km, pts=${res.points.size}")
            }
        }
    }

    private fun fetchOsrmRoute(origin: GeoPoint, dest: GeoPoint): OsrmRoute? {
        val servers = listOf(
            "https://router.project-osrm.org",
            "https://routing.openstreetmap.de/routed-car"
        )
        val extras = listOf("&exclude=ferry", "")

        for (base in servers) {
            for (extra in extras) {
                val urlStr = "$base/route/v1/driving/" +
                        "${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}" +
                        "?overview=full&geometries=polyline&steps=false&alternatives=false&continue_straight=true$extra"
                val res = runCatching { requestOsrm(urlStr) }.getOrNull()
                if (res != null) return res
            }
        }
        return null
    }

    private fun requestOsrm(urlStr: String): OsrmRoute? {
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
            Log.d(TAG, "OSRM code=$code body=${resp.take(200)}")
            if (code !in 200..299) return null

            val root = JSONObject(resp)
            if (root.optString("code") != "Ok") return null
            val arr = root.optJSONArray("routes") ?: JSONArray()
            if (arr.length() == 0) return null
            val r0 = arr.getJSONObject(0)
            val poly = r0.optString("geometry")
            val durSec = (r0.optDouble("duration", 0.0)).toLong()
            val dist = (r0.optDouble("distance", 0.0)).toLong()
            val pts = decodePolylineE5ToGeo(poly)
            if (pts.isEmpty()) null else OsrmRoute(pts, durSec, dist)
        } catch (e: Exception) {
            Log.w(TAG, "OSRM error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // Polyline precision 5 (OSRM default)
    private fun decodePolylineE5ToGeo(encoded: String): List<GeoPoint> {
        val path = ArrayList<GeoPoint>()
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
            path.add(GeoPoint(lat / 1E5, lng / 1E5))
        }
        return path
    }
}
