package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivityOtherEmergencyBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtherEmergencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtherEmergencyBinding
    private lateinit var auth: FirebaseAuth
    private var selectedEmergency: String? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var exactLocation: String = ""

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val SMS_PERMISSION_REQUEST_CODE = 101
    private var lastReportTime: Long = 0

    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    // ==== Tagum radius fallback (center ~City Hall; generous radius buffer) ====
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f // ~11 km buffer around center

    private var tagumOk: Boolean = false // set true if text mentions Tagum OR inside Tagum radius

    private val TARGET_STATION_NODE = "MabiniFireStation"

    private val profileKeyByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaProfile",
        "CanocotanFireStation"  to "CanocotanProfile",
        "MabiniFireStation"     to "MabiniProfile"
    )

    // "OtherEmergency" nodes
    private val otherEmergencyNodeByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaOtherEmergency",
        "CanocotanFireStation"  to "CanocotanOtherEmergency",
        "MabiniFireStation"     to "MabiniOtherEmergency"
    )

    private data class StationInfo(
        val stationNode: String,
        val name: String,
        val contact: String,
        val lat: Double,
        val lon: Double,
        val reportNode: String
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { hideLoadingDialog() } }
        override fun onLost(network: Network) { runOnUiThread { showLoadingDialog("No internet connection") } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityOtherEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Start disabled until both: (1) emergency selected, (2) tagumOk true
        updateSendEnabled()

        // Location permission check & fetch
        checkPermissionsAndGetLocation()

        updateEmergencyText("Select an Emergency")

        binding.floodingButton.setOnClickListener { handleEmergencySelection("Flooding") }
        binding.buildingCollapseButton.setOnClickListener { handleEmergencySelection("Building Collapse") }
        binding.gasLeakButton.setOnClickListener { handleEmergencySelection("Gas Leak") }
        binding.fallenTreeButton.setOnClickListener { handleEmergencySelection("Fallen Tree") }

        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }

        binding.sendButton.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastReportTime >= 5 * 60 * 1000) {
                sendEmergencyReport(now)
            } else {
                val wait = (5 * 60 * 1000 - (now - lastReportTime)) / 1000
                Toast.makeText(this, "Please wait $wait seconds before submitting again.", Toast.LENGTH_LONG).show()
            }
        }

        // Optional: ask for SMS permission proactively (since we send notifications)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    // ---- Connectivity ----
    private fun isConnected(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }

    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    // ---- Permissions & Location ----
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) {
            getLastLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getLastLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                latitude = loc.latitude
                longitude = loc.longitude
                // kick off reverse-geocode
                FetchBarangayAddressTask(this, latitude, longitude).execute()
                // also compute radius check early (in case geocode fails)
                evaluateTagumGateWith(null)
            } else {
                Toast.makeText(this, "Unable to get current location", Toast.LENGTH_SHORT).show()
                evaluateTagumGateWith(null)
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
            evaluateTagumGateWith(null)
        }
    }

    // ---- Emergency selection / UI ----
    private fun handleEmergencySelection(type: String) {
        selectedEmergency = type
        updateButtonAppearance(selectedEmergency)
        updateEmergencyText("$type Selected")
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        val enabled = (selectedEmergency != null) && tagumOk
        binding.sendButton.isEnabled = enabled
    }

    private fun updateButtonAppearance(selected: String?) {
        resetButtonAppearance(binding.floodingButton)
        resetButtonAppearance(binding.buildingCollapseButton)
        resetButtonAppearance(binding.gasLeakButton)
        resetButtonAppearance(binding.fallenTreeButton)
        when (selected) {
            "Flooding" -> binding.floodingButton.alpha = 0.5f
            "Building Collapse" -> binding.buildingCollapseButton.alpha = 0.5f
            "Gas Leak" -> binding.gasLeakButton.alpha = 0.5f
            "Fallen Tree" -> binding.fallenTreeButton.alpha = 0.5f
        }
    }

    private fun resetButtonAppearance(button: MaterialCardView) { button.alpha = 1.0f }

    private fun updateEmergencyText(text: String) {
        binding.title.text = text
        binding.title.setTextColor(resources.getColor(android.R.color.holo_red_dark))
    }

    // ---- Tagum helpers (same logic as FireLevelActivity) ----
    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val d = calculateDistance(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON)
        return d <= TAGUM_RADIUS_METERS
    }

    private fun looksLikeTagum(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("tagum", ignoreCase = true) // matches "Tagum" or "Tagum City"
    }

    /** Called by FetchBarangayAddressTask when reverse geocoding returns. */
    fun handleFetchedAddress(address: String?) {
        exactLocation = address?.trim().orEmpty().ifEmpty { "Unknown Location" }
        evaluateTagumGateWith(address)
    }

    private fun evaluateTagumGateWith(address: String?) {
        val textOk = looksLikeTagum(address?.trim())
        val geoOk  = isWithinTagumByDistance(latitude, longitude)

        tagumOk = textOk || geoOk

        if (tagumOk) {
            if (address.isNullOrBlank() && geoOk) {
                exactLocation = "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            }
            Toast.makeText(
                this,
                "Location confirmed: ${if (exactLocation.isNotBlank()) exactLocation else "within Tagum radius"}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "Outside Tagum area. You can't submit a report.", Toast.LENGTH_SHORT).show()
        }
        updateSendEnabled()
    }

    // ---- Firebase helpers ----
    private fun anyToString(v: Any?): String = when (v) {
        is String -> v
        is Number -> v.toString()
        else -> ""
    }

    private fun anyToDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Float -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun readStationProfile(
        stationNode: String,
        onDone: (StationInfo?) -> Unit
    ) {
        val db = FirebaseDatabase.getInstance().reference
        val profileKey = profileKeyByStation[stationNode] ?: return onDone(null)
        db.child(stationNode).child(profileKey).get()
            .addOnSuccessListener { s ->
                if (!s.exists()) { onDone(null); return@addOnSuccessListener }
                val name = anyToString(s.child("name").value).ifEmpty { stationNode }
                val contact = anyToString(s.child("contact").value)
                val lat = anyToDouble(s.child("latitude").value)
                val lon = anyToDouble(s.child("longitude").value)
                val reportNode = otherEmergencyNodeByStation[stationNode] ?: return@addOnSuccessListener onDone(null)
                onDone(StationInfo(stationNode, name, contact, lat, lon, reportNode))
            }
            .addOnFailureListener { onDone(null) }
    }

    // ---- Submit report ----
    private fun sendEmergencyReport(currentTime: Long) {
        if (!tagumOk) {
            Toast.makeText(this, "Reporting is allowed only within Tagum.", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = auth.currentUser?.uid
        val type = selectedEmergency
        if (userId == null || type == null) {
            Toast.makeText(this, "Please select an emergency type", Toast.LENGTH_SHORT).show()
            return
        }

        val userDB = FirebaseDatabase.getInstance().getReference("Users")
        userDB.child(userId).get()
            .addOnSuccessListener { snap ->
                val user = snap.getValue(User::class.java)
                if (user == null) {
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                readStationProfile(TARGET_STATION_NODE) { station ->
                    if (station == null) {
                        Toast.makeText(this, "Target station profile not found", Toast.LENGTH_SHORT).show()
                        return@readStationProfile
                    }

                    val dateFmt = SimpleDateFormat("MM/dd/yy", Locale.getDefault())
                    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val formattedDate = dateFmt.format(Date(currentTime))
                    val formattedTime = timeFmt.format(Date(currentTime))
                    val mapsUrl = "https://www.google.com/maps?q=$latitude,$longitude"

                    val otherEmergency = OtherEmergency(
                        emergencyType = type,
                        name = user.name.toString(),
                        contact = user.contact.toString(),
                        date = formattedDate,
                        reportTime = formattedTime,
                        latitude = latitude.toString(),
                        longitude = longitude.toString(),
                        location = mapsUrl,
                        exactLocation = exactLocation,
                        lastReportedTime = currentTime,
                        timestamp = currentTime,
                        read = false,
                        fireStationName = station.name
                    )

                    val db = FirebaseDatabase.getInstance().reference
                    db.child(station.stationNode)
                        .child(station.reportNode) // e.g., MabiniOtherEmergency
                        .push()
                        .setValue(otherEmergency)
                        .addOnSuccessListener {
                            lastReportTime = currentTime
                            Toast.makeText(this, "Emergency report submitted to ${station.name}", Toast.LENGTH_SHORT).show()
                            sendSMSNotificationToStation(
                                stationContact = station.contact.ifEmpty { "N/A" },
                                emergency = otherEmergency
                            )
                            startActivity(Intent(this, DashboardActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed to submit emergency report: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ---- SMS ----
    private fun sendSMSNotificationToStation(stationContact: String, emergency: OtherEmergency) {
        if (stationContact.isEmpty() || stationContact == "N/A") {
            Toast.makeText(this, "Fire station contact not available", Toast.LENGTH_SHORT).show()
            return
        }

        val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            Toast.makeText(this, "SMS permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        val message = """
            OTHER EMERGENCY REPORT
            Type: ${emergency.emergencyType}
            Name: ${emergency.name}
            Date: ${emergency.date}
            Time: ${emergency.reportTime}
            Location: ${emergency.exactLocation}
            Maps: ${emergency.location}
        """.trimIndent()

        try {
            if (message.length > 160) {
                val parts = SmsManager.getDefault().divideMessage(message)
                SmsManager.getDefault().sendMultipartTextMessage(stationContact, null, parts, null, null)
            } else {
                SmsManager.getDefault().sendTextMessage(stationContact, null, message, null, null)
            }
            Toast.makeText(this, "SMS sent to $stationContact", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("OtherEmergency", "Failed to send SMS: ${e.message}")
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Utils ----
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
