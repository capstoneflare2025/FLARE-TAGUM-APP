package com.example.flare_capstone

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import com.example.flare_capstone.databinding.FragmentLocationBottomSheetBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.example.flare_capstone.R
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LocationBottomSheetFragment : BottomSheetDialogFragment(), OnMapReadyCallback {

    private lateinit var binding: FragmentLocationBottomSheetBinding
    private lateinit var map: GoogleMap
    private lateinit var locationUrl: String
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentLocationBottomSheetBinding.inflate(inflater, container, false)

        locationUrl = arguments?.getString("LOCATION_URL") ?: ""
        Log.d("LocationBottomSheet", "Location URL: $locationUrl")  // Debugging line

        // Set up the Google Map
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Initialize the fused location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        return binding.root
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        Log.d("LocationBottomSheet", "Google Map is ready.")  // Debugging line

        // Enable My Location on the map
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Request permission if not granted
            ActivityCompat.requestPermissions(requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        map.isMyLocationEnabled = true

        // Get and display the current user's location
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val currentLocation = LatLng(location.latitude, location.longitude)
                Log.d("LocationBottomSheet", "Current location: $currentLocation")  // Debugging line

                // Add a marker for the current user's location with blue color
                map.addMarker(MarkerOptions().position(currentLocation).title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15f))
            } else {
                showError("Unable to get current location")
            }
        }

        // Only proceed if we have a valid location URL
        if (locationUrl.isNotEmpty()) {
            try {
                // Parse the URL to extract latitude and longitude
                val uri = Uri.parse(locationUrl)
                val query = uri.getQueryParameter("q")

                // Check if the query contains valid lat, lng values
                val latLngString = query?.split(",")
                if (latLngString?.size == 2) {
                    val latitude = latLngString[0].toDoubleOrNull()
                    val longitude = latLngString[1].toDoubleOrNull()

                    // Only proceed if we have valid latitude and longitude
                    if (latitude != null && longitude != null) {
                        val location = LatLng(latitude, longitude)
                        Log.d("LocationBottomSheet", "Valid location: $location")  // Debugging line

                        // Add a marker for the passed location and move the camera
                        map.addMarker(MarkerOptions().position(location).title("Fire Station Location"))
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
                    } else {
                        // Handle invalid latitude/longitude
                        showError("Invalid location data")
                    }
                } else {
                    // Handle invalid query parameter format
                    showError("Invalid location format")
                }
            } catch (e: Exception) {
                // Handle any exceptions that may occur during URI parsing or map setup
                showError("Failed to parse location")
            }
        } else {
            // Handle case where location URL is empty
            showError("Location URL is empty")
        }
    }

    private fun showError(message: String) {
        // Simple error handling for displaying a message in your app
        Log.e("LocationBottomSheet", message)
    }
}
