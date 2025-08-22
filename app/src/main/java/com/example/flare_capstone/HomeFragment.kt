package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.flare_capstone.databinding.FragmentHomeBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.math.pow

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var userLatitude: Double = 0.0
    private var userLongitude: Double = 0.0
    private var canocotanFireStationLatitude: Double = 0.0
    private var canocotanFireStationLongitude: Double = 0.0
    private var laFilipinaFireStationLatitude: Double = 0.0
    private var laFilipinaFireStationLongitude: Double = 0.0
    private var mabiniFireStationLatitude: Double = 0.0
    private var mabiniFireStationLongitude: Double = 0.0

    private lateinit var database: DatabaseReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private var canocotanFetched = false
    private var laFilipinaFetched = false
    private var mabiniFetched = false

    // Station -> Profile node (new schema)
    private val stationProfileKey = mapOf(
        "CanocotanFireStation" to "CanocotanProfile",
        "LaFilipinaFireStation" to "LaFilipinaProfile",
        "MabiniFireStation" to "MabiniProfile"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        fetchFireStationLocations()

        binding.fireButton.setOnClickListener {
            startActivity(Intent(requireActivity(), FireLevelActivity::class.java))
        }
        binding.otherButton.setOnClickListener {
            startActivity(Intent(requireActivity(), OtherEmergencyActivity::class.java))
        }
    }

    private fun fetchFireStationLocations() {
        fetchStationLatLng("CanocotanFireStation") { lat, lng ->
            canocotanFireStationLatitude = lat
            canocotanFireStationLongitude = lng
            canocotanFetched = true
            checkAndLoadMap()
        }
        fetchStationLatLng("LaFilipinaFireStation") { lat, lng ->
            laFilipinaFireStationLatitude = lat
            laFilipinaFireStationLongitude = lng
            laFilipinaFetched = true
            checkAndLoadMap()
        }
        fetchStationLatLng("MabiniFireStation") { lat, lng ->
            mabiniFireStationLatitude = lat
            mabiniFireStationLongitude = lng
            mabiniFetched = true
            checkAndLoadMap()
        }
    }

    // Robust field reads to avoid ClassCast/Long->String crashes
    private fun DataSnapshotDouble(s: com.google.firebase.database.DataSnapshot, key: String): Double {
        val node = s.child(key)
        val v = node.value ?: return 0.0
        return when (v) {
            is Double -> v
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is Float -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun fetchStationLatLng(
        stationNode: String,
        onDone: (Double, Double) -> Unit
    ) {
        val profileKey = stationProfileKey[stationNode] ?: ""

        // Try new schema: <Station>/<StationProfile>
        database.child(stationNode).child(profileKey).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    // Read fields individually and coerce types
                    val lat = DataSnapshotDouble(snap, "latitude")
                    val lng = DataSnapshotDouble(snap, "longitude")
                    onDone(lat, lng)
                } else {
                    // Fallback to old schema: fields under <Station>
                    database.child(stationNode).get()
                        .addOnSuccessListener { oldSnap ->
                            if (oldSnap.exists()) {
                                val fs = oldSnap.getValue(FireStation::class.java)
                                onDone(fs?.latitude ?: 0.0, fs?.longitude ?: 0.0)
                            } else {
                                Toast.makeText(context, "$stationNode not found in database", Toast.LENGTH_SHORT).show()
                                onDone(0.0, 0.0)
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Failed to read $stationNode", Toast.LENGTH_SHORT).show()
                            onDone(0.0, 0.0)
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to read $stationNode", Toast.LENGTH_SHORT).show()
                onDone(0.0, 0.0)
            }
    }

    private fun checkAndLoadMap() {
        if (canocotanFetched && laFilipinaFetched && mabiniFetched) {
            fetchUserLocation()
        }
    }

    private fun fetchUserLocation() {
        if (!isAdded || context == null) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val locationRequest = LocationRequest.create().apply {
                interval = 10_000
                fastestInterval = 5_000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            val locations = locationResult.locations ?: return
            for (location in locations) {
                userLatitude = location.latitude
                userLongitude = location.longitude
                loadMap()
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)

        val dlat = lat2Rad - lat1Rad
        val dlon = lon2Rad - lon1Rad

        val a = Math.sin(dlat / 2).pow(2.0) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(dlon / 2).pow(2.0)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    private fun loadMap() {
        if (userLatitude == 0.0 || userLongitude == 0.0 ||
            canocotanFireStationLatitude == 0.0 || canocotanFireStationLongitude == 0.0 ||
            laFilipinaFireStationLatitude == 0.0 || laFilipinaFireStationLongitude == 0.0 ||
            mabiniFireStationLatitude == 0.0 || mabiniFireStationLongitude == 0.0
        ) {
            Toast.makeText(context, "Location coordinates are invalid", Toast.LENGTH_SHORT).show()
            return
        }

        val userLocation = LatLng(userLatitude, userLongitude)
        val canocotanLocation = LatLng(canocotanFireStationLatitude, canocotanFireStationLongitude)
        val laFilipinaLocation = LatLng(laFilipinaFireStationLatitude, laFilipinaFireStationLongitude)
        val mabiniLocation = LatLng(mabiniFireStationLatitude, mabiniFireStationLongitude)

        val distanceToCanocotan = calculateDistance(userLatitude, userLongitude, canocotanFireStationLatitude, canocotanFireStationLongitude)
        val distanceToLaFilipina = calculateDistance(userLatitude, userLongitude, laFilipinaFireStationLatitude, laFilipinaFireStationLongitude)
        val distanceToMabini = calculateDistance(userLatitude, userLongitude, mabiniFireStationLatitude, mabiniFireStationLongitude)

        val minDistance = minOf(distanceToCanocotan, distanceToLaFilipina, distanceToMabini)
        val nearestStation = when (minDistance) {
            distanceToCanocotan -> canocotanLocation
            distanceToLaFilipina -> laFilipinaLocation
            else -> mabiniLocation
        }

        val nearestStationColor = "#F5F206"
        val farStationColor = "#E87F2E"

        map.addMarker(
            MarkerOptions()
                .position(canocotanLocation)
                .title("Canocotan Fire Station")
                .icon(getColoredMarkerDescriptor(if (nearestStation == canocotanLocation) nearestStationColor else farStationColor))
        )
        map.addMarker(
            MarkerOptions()
                .position(laFilipinaLocation)
                .title("La Filipina Fire Station")
                .icon(getColoredMarkerDescriptor(if (nearestStation == laFilipinaLocation) nearestStationColor else farStationColor))
        )
        map.addMarker(
            MarkerOptions()
                .position(mabiniLocation)
                .title("Mabini Fire Station")
                .icon(getColoredMarkerDescriptor(if (nearestStation == mabiniLocation) nearestStationColor else farStationColor))
        )

        map.addMarker(
            MarkerOptions()
                .position(userLocation)
                .title("Your Location")
                .icon(getColoredMarkerDescriptor("#E00024"))
        )

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (_binding == null) return
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val bounds = LatLngBounds.Builder()
                    .include(userLocation)
                    .include(canocotanLocation)
                    .include(laFilipinaLocation)
                    .include(mabiniLocation)
                    .build()

                val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 200)
                map.animateCamera(cameraUpdate)
            }
        })
    }

    private fun getColoredMarkerDescriptor(colorHex: String): BitmapDescriptor {
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.parseColor(colorHex), hsv)
        return BitmapDescriptorFactory.defaultMarker(hsv[0])
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (::map.isInitialized) map.isMyLocationEnabled = true
            } else {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        _binding = null
    }
}
