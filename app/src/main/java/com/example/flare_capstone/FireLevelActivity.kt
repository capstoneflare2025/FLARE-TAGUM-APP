package com.example.flare_capstone

import android.Manifest
import android.R
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivityFireLevelBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FireLevelActivity : AppCompatActivity() {

    /* ---------------- View / Firebase / Location ---------------- */
    private lateinit var binding: ActivityFireLevelBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /* ---------------- Location State ---------------- */
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    /* ---------------- Request Codes ---------------- */
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val SMS_PERMISSION_REQUEST_CODE = 101

    /* ---------------- Address / Report Control ---------------- */
    private var readableAddress: String? = null
    private var addressHandled = false
    private var lastReportTime: Long = 0

    /* ---------------- Connectivity ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    /* ---------------- Tagum Radius Fallback ---------------- */
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f // ~11 km buffer around center

    // --- Add near your other state vars ---
    private var isResolvingLocation = false
    private var locationConfirmed = false
    private var locationResolveTimer: CountDownTimer? = null
    // NEW: locating dialog (separate from your connectivity dialog)
    private var locatingDialog: AlertDialog? = null
    // add near locatingDialog
    private var locatingDialogText: TextView? = null
    // Overlay spinner with logo
    private var overlayView: View? = null
    private var overlayText: TextView? = null




    /* ---------------- Station Mappings ---------------- */
    private val profileKeyByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaProfile",
        "CanocotanFireStation"  to "CanocotanProfile",
        "MabiniFireStation"     to "MabiniProfile"
    )
    private val reportNodeByStation = mapOf(
        "LaFilipinaFireStation" to "LaFilipinaFireReport",
        "CanocotanFireStation"  to "CanocotanFireReport",
        "MabiniFireStation"     to "MabiniFireReport"
    )

    private data class StationInfo(
        val stationNode: String,
        val name: String,
        val contact: String,
        val lat: Double,
        val lon: Double,
        val reportNode: String
    )

    /* ---------------- Network Callback ---------------- */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && !locationConfirmed) {
                    // Nudge the hint while still confirming
                    beginLocationConfirmation("Confirming location…")
                }
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (!locationConfirmed) {
                    beginLocationConfirmation("Waiting for internet…")
                }
            }
        }
    }


    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFireLevelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Before requesting location, show the confirming state
        beginLocationConfirmation()


        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Initial connectivity & listener
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Location + UI
        checkPermissionsAndGetLocation()
        populateSpinners()

        // Buttons
        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
        binding.sendButton.setOnClickListener { showSendConfirmationDialog() }


        // FCM topic
        FirebaseMessaging.getInstance().subscribeToTopic("all")

        // SMS Permission
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


    /* =========================================================
     * Connectivity
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(com.example.flare_capstone.R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(com.example.flare_capstone.R.id.loading_message)?.text = message
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    /* =========================================================
     * Permissions & Location
     * ========================================================= */
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) {
            requestLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun populateSpinners() {
        binding.spinnerHour.dropDownWidth = 150
        binding.spinnerMinute.dropDownWidth = 150
        binding.spinnerAmpm.dropDownWidth = 150

        val hourAdapter = ArrayAdapter(this, R.layout.simple_spinner_item, (1..12).toList())
        hourAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        binding.spinnerHour.adapter = hourAdapter

        val minuteAdapter = ArrayAdapter(this, R.layout.simple_spinner_item, (0..59).toList())
        minuteAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        binding.spinnerMinute.adapter = minuteAdapter

        val ampmAdapter = ArrayAdapter(this, R.layout.simple_spinner_item, listOf("AM", "PM"))
        ampmAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        binding.spinnerAmpm.adapter = ampmAdapter
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10_000L
            fastestInterval = 5_000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                latitude = it.latitude
                longitude = it.longitude
                fusedLocationClient.removeLocationUpdates(this)
                // Do NOT end confirmation yet — we must wait for address resolution
                updateLocatingDialog("Reverse geocoding…")
                FetchBarangayAddressTask(this@FireLevelActivity, latitude, longitude).execute()
            }
        }
    }


    /* =========================================================
     * Tagum Checks
     * ========================================================= */
    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val d = calculateDistance(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON)
        return d <= TAGUM_RADIUS_METERS
    }

    private fun looksLikeTagum(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("tagum", ignoreCase = true)
    }

    /* =========================================================
     * Report Flow
     * ========================================================= */
    private fun checkAndSendAlertReport() {

        if (!locationConfirmed) {
            if (!isResolvingLocation) beginLocationConfirmation()
            Toast.makeText(this, "Cannot submit yet — location not confirmed.", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastReportTime >= 5 * 60 * 1000) {
            binding.sendButton.isEnabled = false
            binding.progressIcon.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            binding.progressText.visibility = View.VISIBLE
            sendAlertReport(now)
        } else {
            val waitMs = 5 * 60 * 1000 - (now - lastReportTime)
            val originalText = binding.sendButton.text.toString()
            Toast.makeText(this, "Please wait ${waitMs / 1000} seconds before submitting again.", Toast.LENGTH_LONG).show()
            binding.sendButton.isEnabled = false
            object : CountDownTimer(waitMs, 1000) {
                override fun onTick(ms: Long) { binding.sendButton.text = "Wait (${ms / 1000})" }
                override fun onFinish() { binding.sendButton.text = originalText; binding.sendButton.isEnabled = true }
            }.start()
        }
    }

    private fun getFormattedTime(): String {
        val hour = binding.spinnerHour.selectedItem.toString()
        val minute = binding.spinnerMinute.selectedItem.toString().padStart(2, '0')
        val ampm = binding.spinnerAmpm.selectedItem.toString()
        return "$hour:$minute - $ampm"
    }

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
                val report = reportNodeByStation[stationNode] ?: return@addOnSuccessListener onDone(null)
                onDone(StationInfo(stationNode, name, contact, lat, lon, report))
            }
            .addOnFailureListener { onDone(null) }
    }

    private fun showSendConfirmationDialog() {

        if (!locationConfirmed) {
            // Keep spinner going and inform the user
            if (!isResolvingLocation) beginLocationConfirmation()
            Toast.makeText(this, "Please wait — confirming your location…", Toast.LENGTH_SHORT).show()
            return
        }

        // Gather inputs
        val fireStartTime = getFormattedTime()
        val affectedHousesStr = binding.housesAffectedInput.text?.toString()?.trim().orEmpty()

        // Basic validation before we even show the dialog
        if (affectedHousesStr.isEmpty()) {
            Toast.makeText(this, "Please enter number of houses affected.", Toast.LENGTH_SHORT).show()
            return
        }
        val affectedHouses = affectedHousesStr.toIntOrNull()
        if (affectedHouses == null || affectedHouses < 0) {
            Toast.makeText(this, "Please enter a valid number of houses.", Toast.LENGTH_SHORT).show()
            return
        }

        // Compute alert level (same logic used in sendAlertReport)
        val alertLevel = when {
            affectedHouses >= 2 -> "2nd alarm"
            affectedHouses == 1 -> "1st alarm"
            else -> "Unknown alarm"
        }

        // Address (or fallback to coordinates if address not yet resolved)
        val addr = when {
            !readableAddress.isNullOrBlank() -> readableAddress!!.trim()
            latitude != 0.0 || longitude != 0.0 -> "GPS: $latitude, $longitude"
            else -> "Not available yet"
        }

        // Current date-time for user clarity (this is not the reportTime you compute later)
        val now = Date(System.currentTimeMillis())
        val dateStr = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)

        // Build a readable summary
        val message = buildString {
            appendLine("Please confirm the details below:")
            appendLine()
            appendLine("• Date: $dateStr")
            appendLine()
            appendLine("• Time: $timeStr")
            appendLine()
            appendLine("• Fire Start Time: $fireStartTime")
            appendLine()
            appendLine("• Houses Affected: $affectedHousesStr")
            appendLine()
            appendLine("• Alert Level: $alertLevel")
            appendLine()
            appendLine("• Location: $addr")
        }

        // Show dialog
        AlertDialog.Builder(this)
            .setTitle("Confirm Fire Report")
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ ->
                // Proceed with your existing flow
                checkAndSendAlertReport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun sendAlertReport(currentTime: Long) {
        val fireStartTime = getFormattedTime()
        val affectedHousesStr = binding.housesAffectedInput.text.toString()
        if (fireStartTime.isEmpty() || affectedHousesStr.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            binding.progressIcon.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.progressText.visibility = View.GONE
            binding.sendButton.isEnabled = true
            return
        }

        val affectedHouses = affectedHousesStr.toInt()
        val alertLevel = when {
            affectedHouses >= 2 -> "2nd alarm"
            affectedHouses == 1 -> "1st alarm"
            else -> "Unknown alarm"
        }

        val userId = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            binding.progressIcon.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.progressText.visibility = View.GONE
            binding.sendButton.isEnabled = true
            return
        }

        FirebaseDatabase.getInstance().getReference("Users").child(userId).get()
            .addOnSuccessListener { userSnap ->
                val user = userSnap.getValue(User::class.java) ?: run {
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    binding.progressIcon.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.progressText.visibility = View.GONE
                    binding.sendButton.isEnabled = true
                    return@addOnSuccessListener
                }

                readStationProfile("LaFilipinaFireStation") { la ->
                    readStationProfile("CanocotanFireStation") { cano ->
                        readStationProfile("MabiniFireStation") { mabini ->
                            val stations = listOfNotNull(la, cano, mabini)
                            if (stations.isEmpty()) {
                                Toast.makeText(this, "No station profiles found", Toast.LENGTH_SHORT).show()
                                binding.progressIcon.visibility = View.GONE
                                binding.progressBar.visibility = View.GONE
                                binding.progressText.visibility = View.GONE
                                binding.sendButton.isEnabled = true
                                return@readStationProfile
                            }

                            val nearest = stations.minByOrNull {
                                calculateDistance(latitude, longitude, it.lat, it.lon)
                            }!!

                            val formattedDate = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(currentTime))
                            val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))

                            val fireReport = FireReport(
                                name = user.name.toString(),
                                contact = user.contact.toString(),
                                fireStartTime = fireStartTime,
                                numberOfHousesAffected = affectedHouses,
                                alertLevel = alertLevel,
                                date = formattedDate,
                                reportTime = formattedTime,
                                latitude = latitude,
                                longitude = longitude,
                                location = "https://www.google.com/maps?q=$latitude,$longitude",
                                exactLocation = readableAddress.orEmpty(),
                                timeStamp = currentTime,
                                status = "Pending",
                                fireStationName = nearest.name,
                                read = false
                            )

                            val db = FirebaseDatabase.getInstance().reference
                            db.child(nearest.stationNode)
                                .child(nearest.reportNode)
                                .push()
                                .setValue(fireReport)
                                .addOnSuccessListener {
                                    lastReportTime = currentTime
                                    val originalText = binding.sendButton.text.toString()
                                    val waitMs = 5 * 60 * 1000
                                    object : CountDownTimer(waitMs.toLong(), 1000) {
                                        override fun onTick(ms: Long) { binding.sendButton.text = "Wait (${ms / 1000})"; binding.sendButton.isEnabled = false }
                                        override fun onFinish() { binding.sendButton.text = originalText; binding.sendButton.isEnabled = true }
                                    }.start()

                                    Toast.makeText(this, "Report submitted to ${nearest.name}", Toast.LENGTH_SHORT).show()
                                    binding.progressIcon.visibility = View.GONE
                                    binding.progressBar.visibility = View.GONE
                                    binding.progressText.visibility = View.GONE

                                    sendSMSToNearestStation(
                                        stationContact = nearest.contact.ifEmpty { "N/A" },
                                        alertLevel = alertLevel,
                                        date = formattedDate,
                                        time = formattedTime,
                                        fireStartTime = fireStartTime,
                                        userName = user.name.toString()
                                    )

                                    startActivity(Intent(this, DashboardActivity::class.java))
                                    finish()
                                }
                                .addOnFailureListener {
                                    binding.progressIcon.visibility = View.GONE
                                    binding.progressBar.visibility = View.GONE
                                    binding.progressText.visibility = View.GONE
                                    binding.sendButton.isEnabled = true
                                    Toast.makeText(this, "Failed to submit fire report: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                }
            }
            .addOnFailureListener {
                binding.progressIcon.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.progressText.visibility = View.GONE
                binding.sendButton.isEnabled = true
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /* =========================================================
     * SMS
     * ========================================================= */
    private fun sendSMSToNearestStation(
        stationContact: String,
        alertLevel: String,
        date: String,
        time: String,
        fireStartTime: String,
        userName: String
    ) {
        if (stationContact.isEmpty() || stationContact == "N/A") {
            Toast.makeText(this, "Station contact not available. SMS not sent.", Toast.LENGTH_SHORT).show()
            return
        }

        val addr = readableAddress?.trim().orEmpty()
        val message = """
            FLARE FIRE REPORT
            Full Name: $userName
            Alert Level: $alertLevel
            Date-Time: $date - $time
            Fire Start: $fireStartTime
            Location: $addr
        """.trimIndent()

        val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            Toast.makeText(this, "SMS permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (message.length > 160) {
                val parts = SmsManager.getDefault().divideMessage(message)
                SmsManager.getDefault().sendMultipartTextMessage(stationContact, null, parts, null, null)
            } else {
                SmsManager.getDefault().sendTextMessage(stationContact, null, message, null, null)
            }
            Toast.makeText(this, "SMS sent to $stationContact", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("FireLevelActivity", "Failed to send SMS: ${e.message}")
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /* =========================================================
     * Utilities
     * ========================================================= */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    // Accept Tagum if either address text says "Tagum" OR device is inside the Tagum radius
    fun handleFetchedAddress(address: String?) {
        val cleaned = address?.trim().orEmpty()
        val textOk = looksLikeTagum(cleaned)
        val geoOk  = isWithinTagumByDistance(latitude, longitude)

        val finalAddress = when {
            cleaned.isNotBlank() -> cleaned
            geoOk -> "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            else -> ""
        }

        readableAddress = finalAddress

        if (textOk || geoOk) {
            endLocationConfirmation(true, "Location confirmed${if (finalAddress.isNotBlank()) ": $finalAddress" else ""}")
        } else {
            endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
        }
    }


    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false

        // Show non-blocking spinner dialog (keep Send button enabled)
        showLocatingDialog(hint)

        // Start a countdown that updates the dialog text
        locationResolveTimer?.cancel()
        locationResolveTimer = object : CountDownTimer(25_000, 1_000) {
            override fun onTick(ms: Long) {
                updateLocatingDialog("$hint ${ms / 1000}s")
            }
            override fun onFinish() {
                if (!locationConfirmed) {
                    updateLocatingDialog("Still confirming location… check internet.")
                    Toast.makeText(this@FireLevelActivity, "Slow internet: still confirming location.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun endLocationConfirmation(success: Boolean, message: String) {
        isResolvingLocation = false
        locationConfirmed = success

        hideLocatingDialog()

        if (message.isNotBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        locationResolveTimer?.cancel()
        locationResolveTimer = null
    }

    private fun showLocatingDialog(initialText: String) {
        if (locatingDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@FireLevelActivity).apply { isIndeterminate = true })
            locatingDialogText = TextView(this@FireLevelActivity).apply {
                text = initialText
                setPadding(32, 0, 0, 0)
                textSize = 16f
            }
            addView(locatingDialogText)
        }
        locatingDialog = AlertDialog.Builder(this)
            .setTitle("Detecting location")
            .setView(content)
            .setCancelable(true)
            .create()
        locatingDialog?.show()
    }

    private fun updateLocatingDialog(text: String) {
        locatingDialogText?.text = text
    }

    private fun hideLocatingDialog() {
        locatingDialog?.dismiss()
        locatingDialog = null
        locatingDialogText = null
    }


}
