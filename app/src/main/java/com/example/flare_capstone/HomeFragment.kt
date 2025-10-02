package com.example.flare_capstone

/* =========================================================
 * HomeFragment.kt (OSRM • draw route only on station tap)
 * - Single active route with proper toggle (tap again to hide)
 * - Prevents duplicate/stale routes via request generation guard
 * - Fixed getStationLatLngByTitle copy/paste bug
 * ========================================================= */

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.flare_capstone.databinding.FragmentHomeBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlin.math.pow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class HomeFragment : Fragment(), OnMapReadyCallback {

    /* ---------------- View binding ---------------- */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /* ---------------- Firebase / Auth ---------------- */
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private var userRef: DatabaseReference? = null
    private var userListener: ValueEventListener? = null
    private var navView: NavigationView? = null

    /* ---------------- Location / Map ---------------- */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap
    private var mapReady = false
    private var locationUpdatesStarted = false

    // user + station coords
    private var userLatitude = 0.0
    private var userLongitude = 0.0
    private var canocotanLat = 0.0
    private var canocotanLng = 0.0
    private var laFilipinaLat = 0.0
    private var laFilipinaLng = 0.0
    private var mabiniLat = 0.0
    private var mabiniLng = 0.0

    private var canocotanFetched = false
    private var laFilipinaFetched = false
    private var mabiniFetched = false

    /* ---------------- Marker/icon cache ---------------- */
    private var iconNearest: BitmapDescriptor? = null
    private var iconOther: BitmapDescriptor? = null
    private var iconUser: BitmapDescriptor? = null

    private var userMarker: Marker? = null
    private val stationMarkers = mutableMapOf<String, Marker>() // key: station title
    private var cameraFittedOnce = false

    // Update throttle (markers)
    private var lastUpdateLat = 0.0
    private var lastUpdateLng = 0.0
    private var lastUpdateMs = 0L

    /* ---------------- Selected route (only one) ---------------- */
    private var selectedStationTitle: String? = null
    private var activePolyline: Polyline? = null
    private var activeDistanceMeters: Long? = null
    private var activeDurationSec: Long? = null
    private val COLOR_ACTIVE = Color.BLUE
    private val ROUTE_WIDTH_PX = 10f

    // Prevent stale/double drawings from in-flight OSRM calls
    private val routeRequestSeq = AtomicInteger(0)

    /* ---------------- Constants / Helpers ---------------- */
    private val RES_STATION_NEAREST = R.drawable.ic_station_shortest
    private val RES_STATION_LONGEST = R.drawable.ic_station_longest
    private val RES_USER_LOCATION  = R.drawable.ic_user_location

    private val TINT_NEAREST: Int? = null
    private val TINT_LONGEST_OR_OTHER: Int? = null
    private val TINT_USER: Int? = null

    private val STATION_W_DP: Int? = 25
    private val STATION_H_DP: Int? = 30
    private val USER_W_DP:   Int? = 30
    private val USER_H_DP:   Int? = 35

    private val stationProfileKey = mapOf(
        "CanocotanFireStation" to "CanocotanProfile",
        "LaFilipinaFireStation" to "LaFilipinaProfile",
        "MabiniFireStation" to "MabiniProfile"
    )

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /* ---------------- Permissions ---------------- */
    private val locationPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            enableMyLocationSafely()
            startLocationUpdates()
        } else {
            context?.let { Toast.makeText(it, "Location permission denied", Toast.LENGTH_SHORT).show() }
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        auth = FirebaseAuth.getInstance()

        (childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment)?.getMapAsync(this)

        // Load stations
        fetchFireStationLocations()

        // Buttons
        binding.fireButton.setOnClickListener {
            startActivity(Intent(requireActivity(), FireLevelActivity::class.java))
        }
        binding.otherButton.setOnClickListener {
            startActivity(Intent(requireActivity(), OtherEmergencyActivity::class.java))
        }

        // Toolbar + Drawer
        val topBar = view.findViewById<MaterialToolbar?>(R.id.topAppBar)
            ?: requireActivity().findViewById(R.id.topAppBar)
        val drawer: DrawerLayout? = view.findViewById(R.id.drawer_layout)
            ?: requireActivity().findViewById(R.id.drawer_layout)
        val nav: NavigationView? = view.findViewById(R.id.nav_view)
            ?: requireActivity().findViewById(R.id.nav_view)

        topBar?.setNavigationOnClickListener { drawer?.open() }
        nav?.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { /* already here */ }
                R.id.nav_report_fire -> startActivity(Intent(requireContext(), FireLevelActivity::class.java))
                R.id.nav_my_reports -> startActivity(Intent(requireContext(), MyReportActivity::class.java))
                R.id.nav_logout -> { /* TODO */ }
            }
            drawer?.closeDrawers()
            true
        }

        nav?.let { populateUserHeader(it) }
        navView = nav
        startUserHeaderListener()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesStarted = false
    }

    override fun onResume() {
        super.onResume()
        if (mapReady && hasLocationPermission()) {
            enableMyLocationSafely()
            startLocationUpdates()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        userListener?.let { userRef?.removeEventListener(it) }
        userListener = null
        userRef = null
        navView = null

        // Clean map items
        activePolyline?.remove(); activePolyline = null
        stationMarkers.values.forEach { it.remove() }
        stationMarkers.clear()
        userMarker = null

        _binding = null
    }

    /* =========================================================
     * Map callbacks and location
     * ========================================================= */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        mapReady = true

        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMapToolbarEnabled = false

        // Tap a station to draw/hide its (shortest) route (toggle) with race-guard
        map.setOnMarkerClickListener { marker ->
            val title = marker.title ?: return@setOnMarkerClickListener false
            if (title == "Your Location") return@setOnMarkerClickListener false

            // Toggle: tap same station again -> hide
            if (selectedStationTitle == title && activePolyline != null) {
                clearActiveRoute()
                selectedStationTitle = null
                // cancel any in-flight response
                routeRequestSeq.incrementAndGet()
                postToast("Route hidden")
                return@setOnMarkerClickListener true
            }

            // Show this station's route (and hide any existing)
            if (userLatitude == 0.0 || userLongitude == 0.0) {
                postToast("Waiting for your location…")
                return@setOnMarkerClickListener true
            }

            val dest = getStationLatLngByTitle(title)
            if (dest == null) {
                postToast("Station location not available.")
                return@setOnMarkerClickListener true
            }

            selectedStationTitle = title
            drawSingleRouteOSRM(
                origin = LatLng(userLatitude, userLongitude),
                dest = dest,
                requestedTitle = title
            ) {
                val meters = activeDistanceMeters ?: return@drawSingleRouteOSRM
                val secs = activeDurationSec ?: return@drawSingleRouteOSRM
                val km = meters / 1000.0
                val mins = secs / 60.0
                postToast("$title • ${"%.1f".format(km)} km • ${"%.0f".format(mins)} min")
            }

            marker.showInfoWindow()
            true
        }

        // Tap empty map to clear the drawn route
        map.setOnMapClickListener {
            if (activePolyline != null) {
                clearActiveRoute()
                selectedStationTitle = null
                // cancel any in-flight response
                routeRequestSeq.incrementAndGet()
                postToast("Route cleared")
            }
        }

        if (hasLocationPermission()) {
            enableMyLocationSafely()
            startLocationUpdates()
        } else {
            requestLocationPerms()
        }
    }

    private fun getStationLatLngByTitle(title: String): LatLng? = when (title) {
        "Canocotan Fire Station"   -> LatLng(canocotanLat, canocotanLng)
        "La Filipina Fire Station" -> LatLng(laFilipinaLat, laFilipinaLng)
        "Mabini Fire Station"      -> LatLng(mabiniLat, mabiniLng)
        else -> null
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPerms() {
        locationPermsLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationSafely() {
        if (!::map.isInitialized || !hasLocationPermission()) return
        try { map.isMyLocationEnabled = true } catch (_: SecurityException) {}
    }

    private fun startLocationUpdates() {
        if (!isAdded || locationUpdatesStarted || !hasLocationPermission()) return
        locationUpdatesStarted = true

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10_000L
        ).setMinUpdateIntervalMillis(5_000L).build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            locationUpdatesStarted = false
            context?.let { Toast.makeText(it, "Location permission missing", Toast.LENGTH_SHORT).show() }
        }
    }

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val loc = locationResult.lastLocation ?: return
            userLatitude = loc.latitude
            userLongitude = loc.longitude
            if (mapReady) updateMapIfReady()
        }
    }

    /* =========================================================
     * Firebase (Stations)
     * ========================================================= */
    private fun fetchFireStationLatLng(path: String, onDone: (Double, Double) -> Unit) {
        val profileKey = stationProfileKey[path] ?: ""
        database.child(path).child(profileKey).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    onDone(snap.getDouble("latitude"), snap.getDouble("longitude"))
                } else {
                    database.child(path).get()
                        .addOnSuccessListener { oldSnap ->
                            if (oldSnap.exists()) {
                                onDone(oldSnap.getDouble("latitude"), oldSnap.getDouble("longitude"))
                            } else {
                                Toast.makeText(context, "$path not found in database", Toast.LENGTH_SHORT).show()
                                onDone(0.0, 0.0)
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Failed to read $path", Toast.LENGTH_SHORT).show()
                            onDone(0.0, 0.0)
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to read $path", Toast.LENGTH_SHORT).show()
                onDone(0.0, 0.0)
            }
    }

    private fun fetchFireStationLocations() {
        fetchFireStationLatLng("CanocotanFireStation") { lat, lng ->
            canocotanLat = lat; canocotanLng = lng; canocotanFetched = true; updateMapIfReady()
        }
        fetchFireStationLatLng("LaFilipinaFireStation") { lat, lng ->
            laFilipinaLat = lat; laFilipinaLng = lng; laFilipinaFetched = true; updateMapIfReady()
        }
        fetchFireStationLatLng("MabiniFireStation") { lat, lng ->
            mabiniLat = lat; mabiniLng = lng; mabiniFetched = true; updateMapIfReady()
        }
    }

    /* =========================================================
     * Drawer header (User profile)
     * ========================================================= */
    private fun populateUserHeader(nav: NavigationView) {
        val headerView = nav.getHeaderView(0)
        val nameView = headerView.findViewById<TextView>(R.id.headerName)
        val emailView = headerView.findViewById<TextView>(R.id.headerEmail)
        val avatarView = headerView.findViewById<ImageView>(R.id.headerAvatar)

        val user = auth.currentUser
        if (user == null) {
            nameView.text = "Guest"
            emailView.text = ""
            Glide.with(this).load(R.drawable.ic_profile).transform(CircleCrop()).into(avatarView)
            return
        }

        nameView.text = user.displayName ?: "User"
        emailView.text = user.email ?: ""
        Glide.with(this).load(R.drawable.ic_profile).transform(CircleCrop()).into(avatarView)

        val uid = user.uid
        database.child("Users").child(uid).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) return@addOnSuccessListener
                val fullName = snap.child("name").getValue(String::class.java)
                val email = snap.child("email").getValue(String::class.java)
                val profileB64 = snap.child("profile").getValue(String::class.java)

                if (!fullName.isNullOrBlank()) nameView.text = fullName
                if (!email.isNullOrBlank()) emailView.text = email
                loadAvatarFromBase64Into(profileB64, avatarView)
            }
    }

    private fun startUserHeaderListener() {
        val nav = navView ?: return
        val header = nav.getHeaderView(0)
        val nameView = header.findViewById<TextView>(R.id.headerName)
        val emailView = header.findViewById<TextView>(R.id.headerEmail)
        val avatarView = header.findViewById<ImageView>(R.id.headerAvatar)

        val user = auth.currentUser
        if (user == null) {
            nameView.text = "Guest"
            emailView.text = ""
            avatarView.setImageResource(R.drawable.ic_profile)
            return
        }

        userRef = FirebaseDatabase.getInstance().reference.child("Users").child(user.uid)
        userListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val name   = snapshot.child("name").getValue(String::class.java)
                val email  = snapshot.child("email").getValue(String::class.java)
                val avatar = snapshot.child("profile").getValue(String::class.java)

                if (!name.isNullOrBlank())  nameView.text = name
                if (!email.isNullOrBlank()) emailView.text = email
                loadAvatarFromBase64Into(avatar, avatarView)
            }
            override fun onCancelled(error: DatabaseError) { /* no-op */ }
        }
        userRef?.addValueEventListener(userListener as ValueEventListener)
    }

    /** Base64 -> ImageView (handles data-URL prefixes) */
    private fun loadAvatarFromBase64Into(avatarB64: String?, target: ImageView) {
        if (avatarB64.isNullOrBlank()) {
            target.setImageResource(R.drawable.ic_profile)
            return
        }
        val pureBase64 = avatarB64.substringAfter("base64,", avatarB64)
        try {
            val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
            Glide.with(this)
                .load(bytes)
                .placeholder(R.drawable.ic_profile)
                .error(R.drawable.ic_profile)
                .transform(CircleCrop())
                .into(target)
        } catch (_: Exception) {
            target.setImageResource(R.drawable.ic_profile)
        }
    }

    /* =========================================================
     * Map markers & camera (optimized)
     * ========================================================= */
    private fun ensureIcons() {
        if (iconNearest == null) {
            iconNearest = bitmapFromDrawable(RES_STATION_NEAREST, TINT_NEAREST, STATION_W_DP, STATION_H_DP)
        }
        if (iconOther == null) {
            iconOther = bitmapFromDrawable(RES_STATION_LONGEST, TINT_LONGEST_OR_OTHER, STATION_W_DP, STATION_H_DP)
        }
        if (iconUser == null) {
            iconUser = bitmapFromDrawable(RES_USER_LOCATION, TINT_USER, USER_W_DP, USER_H_DP)
        }
    }

    private fun shouldUpdate(lat: Double, lng: Double): Boolean {
        val now = System.currentTimeMillis()
        val dt = now - lastUpdateMs
        val dMeters = distanceKm(lastUpdateLat, lastUpdateLng, lat, lng) * 1000.0
        return dt >= 3000L || dMeters >= 15.0
    }

    private fun updateMapIfReady() {
        if (!mapReady) return
        if (!(canocotanFetched && laFilipinaFetched && mabiniFetched)) return
        if (userLatitude == 0.0 || userLongitude == 0.0) return
        if (!shouldUpdate(userLatitude, userLongitude)) return

        lastUpdateLat = userLatitude
        lastUpdateLng = userLongitude
        lastUpdateMs = System.currentTimeMillis()

        ensureIcons()

        val user = LatLng(userLatitude, userLongitude)
        val stations = listOf(
            Triple("Canocotan Fire Station", canocotanLat, canocotanLng),
            Triple("La Filipina Fire Station", laFilipinaLat, laFilipinaLng),
            Triple("Mabini Fire Station", mabiniLat, mabiniLng)
        )

        // user marker
        if (userMarker == null) {
            userMarker = map.addMarker(
                MarkerOptions()
                    .position(user)
                    .title("Your Location")
                    .icon(iconUser)
                    .anchor(0.5f, 1f)
            )
        } else {
            userMarker!!.position = user
        }

        // nearest station (for icon only)
        val dists = stations.mapIndexed { idx, t ->
            idx to distanceKm(userLatitude, userLongitude, t.second, t.third)
        }
        val nearestIdx = dists.minByOrNull { it.second }?.first ?: 0

        // place/update station markers
        stations.forEachIndexed { idx, (title, lat, lng) ->
            val pos = LatLng(lat, lng)
            val existing = stationMarkers[title]
            val desiredIcon = if (idx == nearestIdx) iconNearest else iconOther
            if (existing == null) {
                stationMarkers[title] = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title(title)
                        .icon(desiredIcon)
                        .anchor(0.5f, 1f)
                )!!
            } else {
                existing.position = pos
                existing.setIcon(desiredIcon)
            }
        }

        // fit camera once
        if (!cameraFittedOnce && _binding != null) {
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (_binding == null) return
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val b = LatLngBounds.Builder().apply {
                        include(user)
                        stations.forEach { (_, lat, lng) -> include(LatLng(lat, lng)) }
                    }.build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 200))
                    cameraFittedOnce = true
                }
            })
        }

        // Optional: refresh active route as you move, but ensure guard stops duplicates
        selectedStationTitle?.let { title ->
            val dest = getStationLatLngByTitle(title)
            if (dest != null) {
                drawSingleRouteOSRM(
                    origin = LatLng(userLatitude, userLongitude),
                    dest = dest,
                    requestedTitle = title,
                    onDone = null
                )
            }
        }
    }

    // Great-circle distance (Haversine)
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)
        val dlat = lat2Rad - lat1Rad
        val dlon = lon2Rad - lon1Rad
        val a = Math.sin(dlat / 2).pow(2.0) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(dlon / 2).pow(2.0)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /* =========================================================
     * OSRM: fetch + draw ONE shortest route (selected station)
     *  - guarded by request generation + title match to avoid stale draws
     * ========================================================= */
    private fun drawSingleRouteOSRM(
        origin: LatLng,
        dest: LatLng,
        requestedTitle: String,
        onDone: (() -> Unit)? = null
    ) {
        // increment request generation and remember mine
        val mySeq = routeRequestSeq.incrementAndGet()

        // Remove previous polyline immediately
        clearActiveRoute()

        Thread {
            var points: List<LatLng> = emptyList()
            var meters = Long.MAX_VALUE
            var seconds = Long.MAX_VALUE

            try {
                val urlStr =
                    "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}" +
                            "?overview=full&geometries=polyline&steps=false&alternatives=false"

                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(text)
                    val status = json.optString("code", "Error")
                    if (status == "Ok") {
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val r0 = routes.getJSONObject(0)
                            meters = r0.optDouble("distance", Double.MAX_VALUE).toLong()
                            seconds = r0.optDouble("duration", Double.MAX_VALUE).toLong()
                            val geom = r0.optString("geometry", "")
                            if (geom.isNotEmpty()) points = decodePolyline(geom)
                        }
                    } else {
                        val msg = json.optString("message")
                        postToast("OSRM: $status${if (msg.isNotBlank()) " – $msg" else ""}")
                    }
                } else {
                    postToast("OSRM error ($code)")
                }
            } catch (e: Exception) {
                postToast("OSRM failed: ${e.message}")
            }

            requireActivity().runOnUiThread {
                // Discard stale responses (another tap already happened)
                if (routeRequestSeq.get() != mySeq) return@runOnUiThread
                // Also ensure user hasn't changed the selected station
                if (selectedStationTitle != requestedTitle) return@runOnUiThread

                if (points.isNotEmpty()) {
                    activePolyline = map.addPolyline(
                        PolylineOptions()
                            .addAll(points)
                            .width(ROUTE_WIDTH_PX)
                            .color(COLOR_ACTIVE)
                            .geodesic(true)
                    )
                    activeDistanceMeters = meters
                    activeDurationSec = seconds
                } else {
                    // Fallback straight line
                    activePolyline = map.addPolyline(
                        PolylineOptions()
                            .add(origin, dest)
                            .width(ROUTE_WIDTH_PX)
                            .color(COLOR_ACTIVE)
                            .geodesic(true)
                            .pattern(listOf(Dot(), Gap(10f)))
                    )
                    val m = (distanceKm(origin.latitude, origin.longitude, dest.latitude, dest.longitude) * 1000).toLong()
                    activeDistanceMeters = m
                    activeDurationSec = ((m / 1000.0) / 35.0 * 3600).toLong()
                }
                onDone?.invoke()
            }
        }.start()
    }

    private fun clearActiveRoute() {
        activePolyline?.remove()
        activePolyline = null
        activeDistanceMeters = null
        activeDurationSec = null
    }

    /** Polyline decoder (polyline5) */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latD = lat / 1E5
            val lngD = lng / 1E5
            poly.add(LatLng(latD, lngD))
        }
        return poly
    }

    private fun postToast(msg: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    /* =========================================================
     * Drawable -> BitmapDescriptor helper
     * ========================================================= */
    private fun bitmapFromDrawable(
        @DrawableRes resId: Int,
        tint: Int?,
        widthDp: Int?,
        heightDp: Int?
    ): BitmapDescriptor {
        val ctx = requireContext()
        val drawable: Drawable = (AppCompatResources.getDrawable(ctx, resId)
            ?: error("Drawable not found: $resId")).mutate()

        val wrapped = if (tint != null) DrawableCompat.wrap(drawable) else drawable
        if (tint != null) DrawableCompat.setTint(wrapped, tint)

        val wPx = widthDp?.dp() ?: drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
        val hPx = heightDp?.dp() ?: drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
        wrapped.setBounds(0, 0, wPx, hPx)

        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        wrapped.draw(canvas)

        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /* =========================================================
     * DataSnapshot utils
     * ========================================================= */
    private fun DataSnapshot.getDouble(key: String): Double {
        val v = child(key).value ?: return 0.0
        return when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
