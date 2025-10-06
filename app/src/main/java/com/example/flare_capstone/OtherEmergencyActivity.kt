package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.CountDownTimer
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

    // Non-blocking locating dialog + timer
    private var locatingDialog: AlertDialog? = null
    private var locationResolveTimer: android.os.CountDownTimer? = null
    private var isResolvingLocation = false

    // Optional: if you want to reflect success state
    private var locationConfirmed = false

    private val COOLDOWN_MS = 5 * 60 * 1000L
    private var sendCooldownTimer: CountDownTimer? = null
    private var sendOriginalText: CharSequence? = null




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

    // You call registerDefaultNetworkCallback(networkCallback) below, so define it:
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && locatingDialog != null) {
                    updateLocatingDialog("Confirming location…")
                }
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (isResolvingLocation && locatingDialog != null) {
                    updateLocatingDialog("Waiting for internet…")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityOtherEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        beginLocationConfirmation()

        binding.sendButton.isEnabled = false   // 🔒 default disabled

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

        // When user taps Send – add quick guards BEFORE showing the dialog
        binding.sendButton.setOnClickListener {
            val now = System.currentTimeMillis()
            val hasCoords = !(latitude == 0.0 && longitude == 0.0)

            if (!hasCoords) {
                Toast.makeText(this, "Getting your location… please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!tagumOk) {
                Toast.makeText(this, "Reporting is allowed only within Tagum.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedEmergency.isNullOrBlank()) {
                Toast.makeText(this, "Please select an emergency type.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (now - lastReportTime >= 5 * 60 * 1000) {
                showSendConfirmationDialog(now)
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
        locationResolveTimer?.cancel()
        locatingDialog?.dismiss()
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
        updateLocatingDialog("Getting GPS fix…")
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                latitude = loc.latitude
                longitude = loc.longitude
                FetchBarangayAddressTask(this, latitude, longitude).execute()
                evaluateTagumGateWith(null)
            } else {
                // Fallback: request a single update so hasCoords becomes true soon
                val req = com.google.android.gms.location.LocationRequest.create().apply {
                    interval = 10_000L; fastestInterval = 5_000L
                    priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                    numUpdates = 1
                }
                fusedLocationClient.requestLocationUpdates(req, object: com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(res: com.google.android.gms.location.LocationResult) {
                        val l = res.lastLocation ?: return
                        latitude = l.latitude; longitude = l.longitude
                        fusedLocationClient.removeLocationUpdates(this)
                        FetchBarangayAddressTask(this@OtherEmergencyActivity, latitude, longitude).execute()
                        evaluateTagumGateWith(null)
                    }
                }, mainLooper)

                Toast.makeText(this, "Getting location…", Toast.LENGTH_SHORT).show()
                updateLocatingDialog("Waiting for GPS…")
                evaluateTagumGateWith(null) // still runs geo gate (likely false) + disables send
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
            updateLocatingDialog("Location error — retrying…")
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

    // Central place to decide if "Send" can be pressed
    private fun updateSendEnabled() {
        val hasCoords = !(latitude == 0.0 && longitude == 0.0)
        val enabled = (selectedEmergency != null) && tagumOk && hasCoords
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
    /** Called by FetchBarangayAddressTask when reverse geocoding returns. */
    fun handleFetchedAddress(address: String?) {
        exactLocation = address?.trim().orEmpty().ifEmpty { "Unknown Location" }
        evaluateTagumGateWith(address)

        if (tagumOk) {
            val msg = if (exactLocation.isNotBlank() && exactLocation != "Unknown Location")
                "Location confirmed: $exactLocation"
            else
                "Location confirmed within Tagum vicinity"
            endLocationConfirmation(true, msg)
        } else {
            endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
        }
    }


    // evaluateTagumGateWith() – keep, but ensure we re-run the enable logic
    private fun evaluateTagumGateWith(address: String?) {
        val textOk = looksLikeTagum(address?.trim())
        val geoOk  = isWithinTagumByDistance(latitude, longitude)
        tagumOk = textOk || geoOk

        if (tagumOk) {
            if (address.isNullOrBlank() && geoOk) {
                exactLocation = "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            }
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

    private fun showSendConfirmationDialog(currentTime: Long) {

        val hasCoords = !(latitude == 0.0 && longitude == 0.0)
        if (!hasCoords) {
            Toast.makeText(this, "Getting your location… please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tagumOk) {
            Toast.makeText(this, "Reporting is allowed only within Tagum.", Toast.LENGTH_SHORT).show()
            return
        }
        val type = selectedEmergency
        if (type.isNullOrBlank()) {
            Toast.makeText(this, "Please select an emergency type.", Toast.LENGTH_SHORT).show()
            return
        }

        // Build user-facing summary
        val dateStr = SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(Date(currentTime))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))
        val addr = exactLocation.ifBlank {
            if (latitude != 0.0 || longitude != 0.0)
                "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            else
                "Not available yet"
        }

        val message = buildString {
            appendLine("Please confirm the details below:")
            appendLine()
            appendLine("• Type: $type")
            appendLine()
            appendLine("• Date: $dateStr")
            appendLine()
            appendLine("• Time: $timeStr")
            appendLine()
            appendLine("• Location: $addr")
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Emergency Report")
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ ->
                // Proceed with your existing flow
                sendEmergencyReport(currentTime)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // ---- Submit report (routes to nearest station) ----
    private fun sendEmergencyReport(currentTime: Long) {
        val hasCoords = !(latitude == 0.0 && longitude == 0.0)
        if (!hasCoords || !tagumOk) {
            Toast.makeText(this, "Location not confirmed in Tagum yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = auth.currentUser?.uid
        val type = selectedEmergency
        if (userId == null || type.isNullOrBlank()) {
            Toast.makeText(this, "Please select an emergency type.", Toast.LENGTH_SHORT).show()
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

                // Fetch all station profiles, pick nearest
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
                                fireStationName = nearest.name
                            )

                            val db = FirebaseDatabase.getInstance().reference
                            db.child(nearest.stationNode)
                                .child(nearest.reportNode) // e.g., LaFilipinaOtherEmergency
                                .push()
                                .setValue(otherEmergency)
                                .addOnSuccessListener {
                                    lastReportTime = currentTime
                                    Toast.makeText(this, "Emergency report submitted to ${nearest.name}", Toast.LENGTH_SHORT).show()
                                    sendSMSNotificationToStation(
                                        stationContact = nearest.contact.ifEmpty { "N/A" },
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

    private fun showLocatingDialog(initialText: String) {
        if (locatingDialog?.isShowing == true) return
        val padding = resources.displayMetrics.density.times(16).toInt()

        val progress = android.widget.ProgressBar(this).apply { isIndeterminate = true }
        val textView = android.widget.TextView(this).apply {
            text = initialText
            setPadding(padding, 0, 0, 0)
            textSize = 16f
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(padding * 2, padding * 2, padding * 2, padding * 2)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(progress)
            addView(textView)
            id = android.R.id.content // so we can find it later
        }

        locatingDialog = AlertDialog.Builder(this)
            .setView(row)
            .setCancelable(true) // user may dismiss; guards still prevent bad submits
            .create()
        locatingDialog?.show()
    }

    private fun updateLocatingDialog(text: String) {
        val root = locatingDialog?.window?.decorView ?: return
        val vg = root.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val tv = (vg.getChildAt(0) as? android.widget.LinearLayout)?.getChildAt(1) as? android.widget.TextView
        tv?.text = text
    }

    private fun hideLocatingDialog() {
        locatingDialog?.dismiss()
        locatingDialog = null
    }

    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false
        showLocatingDialog(hint)

        locationResolveTimer?.cancel()
        locationResolveTimer = object : android.os.CountDownTimer(25_000, 1_000) {
            override fun onTick(ms: Long) {
                updateLocatingDialog("$hint ${ms / 1000}s")
            }
            override fun onFinish() {
                if (!locationConfirmed) {
                    updateLocatingDialog("Still confirming location… check internet.")
                    Toast.makeText(this@OtherEmergencyActivity, "Slow internet: still confirming location.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun endLocationConfirmation(success: Boolean, toast: String = "") {
        isResolvingLocation = false
        locationConfirmed = success
        hideLocatingDialog()
        locationResolveTimer?.cancel()
        locationResolveTimer = null
        if (toast.isNotBlank()) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }


}
