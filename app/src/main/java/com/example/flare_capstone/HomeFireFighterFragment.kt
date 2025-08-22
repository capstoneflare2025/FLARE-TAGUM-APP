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
import androidx.preference.PreferenceManager
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

    // OSM map
    private lateinit var map: MapView

    // Pins + route
    private var myPin: Marker? = null
    private var incidentPin: Marker? = null
    private var routeLine: Polyline? = null

    private var stationPrefix: String? = null
    private var reportPath: String? = null

    private var ongoingIncidentId: String? = null
    private var currentReportPoint: GeoPoint? = null
    private var lastMyPoint: GeoPoint? = null

    private var reportListener: ValueEventListener? = null
    private var reportRef: Query? = null

    // Routing (OSRM)
    private val bg = Executors.newSingleThreadExecutor()
    private var lastRoutedOrigin: GeoPoint? = null
    private var lastRoutedDest: GeoPoint? = null
    private val routeRecomputeMeters = 25f

    private val locationPerms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grant ->
        val ok = (grant[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (grant[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (ok) {
            startLocationUpdates()
        } else {
            Log.w(TAG, "Location permission denied; routing limited")
        }
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

        // OSM config (user agent)
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
        reportPath  = "$base/${stationPrefix}FireReport"

        binding.completed.setOnClickListener { markCompleted() }

        attachReportListener()
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
        detachReportListener()
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
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            reqPerms.launch(locationPerms)
        }
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

        // Camera fit
        val pts = mutableListOf<GeoPoint>()
        myLoc?.let { pts += it }
        reportLoc?.let { pts += it }

        when (pts.size) {
            1 -> {
                map.controller.setZoom(15.0)
                map.controller.animateTo(pts.first())
            }
            2 -> {
                val bb = BoundingBox.fromGeoPoints(pts)
                try { map.zoomToBoundingBox(bb, true, 120) } catch (_: Throwable) { map.zoomToBoundingBox(bb, true) }
            }
        }
        map.invalidate()

        // Route when both are known
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
        try { fusedLocation.requestLocationUpdates(req, locCallback, requireActivity().mainLooper) }
        catch (_: SecurityException) { Log.w(TAG, "requestLocationUpdates SecurityException") }
    }

    private fun stopLocationUpdates() {
        try { fusedLocation.removeLocationUpdates(locCallback) } catch (_: Exception) {}
    }

    // ---------- Firebase listener (robust to status/format issues) ----------

    private fun attachReportListener() {
        val path = reportPath ?: return
        // Pull recent reports by time; filter for status client-side (case-insensitive).
        reportRef = FirebaseDatabase.getInstance().getReference(path)
            .orderByChild("timeStamp")
            .limitToLast(5)

        reportListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                var pick: DataSnapshot? = null
                var newestTs = Long.MIN_VALUE

                for (c in snap.children) {
                    val statusRaw = c.child("status").getValue(String::class.java)?.trim()
                    val statusOk = statusRaw.equals("Ongoing", ignoreCase = true)
                    val ts = c.child("timeStamp").getValue(Long::class.java) ?: 0L
                    if (statusOk && ts > newestTs) {
                        newestTs = ts
                        pick = c
                    }
                }

                if (pick == null) {
                    ongoingIncidentId = null
                    currentReportPoint = null
                    clearRouteOnly()
                    incidentPin?.let { map.overlays.remove(it); incidentPin = null; map.invalidate() }
                    updatePins(myPin?.position, null)
                    Log.d(TAG, "No ongoing reports (post-filter)")
                    return
                }

                ongoingIncidentId = pick.key

                val lat = getDoubleRelaxed(pick, "latitude")
                val lon = getDoubleRelaxed(pick, "longitude")
                if (lat == null || lon == null) {
                    Log.w(TAG, "Invalid lat/lng from DB; lat='${pick.child("latitude").value}' lon='${pick.child("longitude").value}'")
                    return
                }
                currentReportPoint = GeoPoint(lat, lon)
                Log.d(TAG, "Ongoing report $ongoingIncidentId @ $currentReportPoint")

                updatePins(myPin?.position, currentReportPoint)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "DB cancelled: ${error.message}")
            }
        }
        reportRef?.addValueEventListener(reportListener as ValueEventListener)
    }

    private fun getDoubleRelaxed(node: DataSnapshot, key: String): Double? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun detachReportListener() {
        reportListener?.let { l -> reportRef?.removeEventListener(l) }
        reportListener = null
        reportRef = null
    }

    private fun markCompleted() {
        val id = ongoingIncidentId ?: return
        val path = reportPath ?: return
        FirebaseDatabase.getInstance().getReference("$path/$id").child("status").setValue("Completed")
        clearRouteOnly()
        incidentPin?.let { map.overlays.remove(it); incidentPin = null; map.invalidate() }
        Log.d(TAG, "Marked completed $id")
    }

    private fun clearRouteOnly() {
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        lastRoutedOrigin = null
        lastRoutedDest = null
        map.invalidate()
    }

    // ---------- OSRM Routing (FREE, in-app) with fallback ----------

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
