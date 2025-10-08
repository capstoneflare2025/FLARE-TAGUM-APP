package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivityVehicleAccidentBinding
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VehicleAccidentActivity
 * - Uses your XML (with EditText @id/vehicleAccident, and @id/sendButton / @id/cancelButton).
 * - Finds nearest station from 3 profiles and writes under that station's VehicleAccident node:
 *   LaFilipinaVehicleAccident / CanocotanVehicleAccident / MabiniVehicleAccident
 */
class VehicleAccidentActivity : AppCompatActivity() {

    /* ---------------- View / Firebase / Location ---------------- */
    private lateinit var binding: ActivityVehicleAccidentBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /* ---------------- Location State ---------------- */
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    /* ---------------- Request Codes ---------------- */
    private val locationPerms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /* ---------------- Station Mappings ---------------- */
    private val profileKeyByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaProfile",
        "CanocotanFireStation"  to "CanocotanProfile",
        "MabiniFireStation"     to "MabiniProfile"
    )

    // New: target nodes for Vehicle Accident reports (includes MabiniVehicleAccident as requested)
    private val vehicleAccidentNodeByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaVehicleAccident",
        "CanocotanFireStation"  to "CanocotanVehicleAccident",
        "MabiniFireStation"     to "MabiniVehicleAccident"
    )

    private data class StationInfo(
        val stationNode: String,
        val name: String,
        val contact: String,
        val lat: Double,
        val lon: Double,
        val reportNode: String
    )

    data class VehicleAccidentReport(
        val involved: String = "",
        val reportTime: String = "",          // 24h format HH:mm:ss
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val location: String = "",            // google maps URL
        val name: String = "",                // reporter name (from Users)
        val status: String = "Pending",
        val timeStamp: Long = 0L,
        val fireStationName: String = "",     // nearest station readable name
        val stationContact: String = "",      // nearest station contact
        val read: Boolean = false
    )

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleAccidentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Ask location permissions up front, then get last known ASAP.
        requestLocationPermissionIfNeeded { getOneLocationFix() }

        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }

        binding.sendButton.setOnClickListener {
            sendVehicleAccidentReport()
        }
    }

    /* =========================================================
     * Permissions & Single Fix
     * ========================================================= */
    private val requestPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            getOneLocationFix()
        } else {
            Toast.makeText(this, "Location permission required to attach your location.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestLocationPermissionIfNeeded(onGranted: () -> Unit) {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk || coarseOk) onGranted() else requestPermsLauncher.launch(locationPerms)
    }

    private fun getOneLocationFix() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMaxUpdates(1) // single fix
            .build()

        fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                latitude = loc.latitude
                longitude = loc.longitude
                fusedLocationClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    /* =========================================================
     * Report Flow
     * ========================================================= */
    private fun sendVehicleAccidentReport() {
        val userId = auth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val involved = binding.vehicleAccident.text?.toString()?.trim().orEmpty()
        if (involved.isEmpty()) {
            Toast.makeText(this, "Please describe what’s involved (e.g., vehicles, plate numbers).", Toast.LENGTH_SHORT).show()
            return
        }

        // Pull user (expects fields 'name' and 'contact' like your Fire flow)
        val db = FirebaseDatabase.getInstance().reference
        db.child("Users").child(userId).get()
            .addOnSuccessListener { snap ->
                val user = snap.getValue(User::class.java)
                val reporterName = user?.name?.toString().orEmpty()

                // Read 3 station profiles, pick nearest
                readStationProfile("LaFilipinaFireStation") { la ->
                    readStationProfile("CanocotanFireStation") { cano ->
                        readStationProfile("MabiniFireStation") { mabini ->
                            val stations = listOfNotNull(la, cano, mabini)
                            if (stations.isEmpty()) {
                                Toast.makeText(this, "No station profiles found", Toast.LENGTH_SHORT).show()
                                return@readStationProfile
                            }

                            val nearest = stations.minByOrNull {
                                calculateDistance(latitude, longitude, it.lat, it.lon)
                            }!!

                            // Compose report
                            val now = System.currentTimeMillis()
                            val reportTime24 = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                            val mapsUrl = "https://www.google.com/maps?q=$latitude,$longitude"

                            val report = VehicleAccidentReport(
                                involved = involved,
                                reportTime = reportTime24,
                                latitude = latitude,
                                longitude = longitude,
                                location = mapsUrl,
                                name = reporterName,
                                status = "Pending",
                                timeStamp = now,
                                fireStationName = nearest.name,
                                stationContact = nearest.contact,
                                read = false
                            )

                            // Write under the nearest station's VehicleAccident node (includes MabiniVehicleAccident)
                            db.child(nearest.stationNode)
                                .child(nearest.reportNode) // already mapped to *VehicleAccident
                                .push()
                                .setValue(report)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Vehicle report sent to ${nearest.name}", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this, DashboardActivity::class.java))
                                    finish()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this, "Failed to submit: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to fetch user: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /* =========================================================
     * Firebase helpers
     * ========================================================= */
    private fun readStationProfile(
        stationNode: String,
        onDone: (StationInfo?) -> Unit
    ) {
        val db = FirebaseDatabase.getInstance().reference
        val profileKey = profileKeyByStation[stationNode] ?: return onDone(null)
        val accidentNode = vehicleAccidentNodeByStation[stationNode] ?: return onDone(null)

        db.child(stationNode).child(profileKey).get()
            .addOnSuccessListener { s ->
                if (!s.exists()) { onDone(null); return@addOnSuccessListener }
                val name = s.child("name").value?.toString().orEmpty().ifEmpty { stationNode }
                val contact = s.child("contact").value?.toString().orEmpty()
                val lat = s.child("latitude").value.toString().toDoubleOrNull() ?: 0.0
                val lon = s.child("longitude").value.toString().toDoubleOrNull() ?: 0.0
                onDone(
                    StationInfo(
                        stationNode = stationNode,
                        name = name,
                        contact = contact,
                        lat = lat,
                        lon = lon,
                        reportNode = accidentNode
                    )
                )
            }
            .addOnFailureListener { onDone(null) }
    }

    /* =========================================================
     * Utilities
     * ========================================================= */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}

/* ---------------------------------------------------------
 * Your existing User model should have at least:
 *   name: String? and contact: String?
 * If you don’t already have it, here’s a minimal version:
 * --------------------------------------------------------- */

