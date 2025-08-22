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
import android.view.View
import android.widget.ArrayAdapter
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

    private lateinit var binding: ActivityFireLevelBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val SMS_PERMISSION_REQUEST_CODE = 101

    private var readableAddress: String? = null
    private var addressHandled = false
    private var lastReportTime: Long = 0

    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    // Station profile/report mapping
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { hideLoadingDialog() } }
        override fun onLost(network: Network) { runOnUiThread { showLoadingDialog("No internet connection") } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFireLevelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        checkPermissionsAndGetLocation()
        populateSpinners()

        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
        binding.sendButton.setOnClickListener { checkAndSendAlertReport() }

        FirebaseMessaging.getInstance().subscribeToTopic("all")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

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

        val minuteAdapter = ArrayAdapter(this, R.layout.simple_spinner_item, (1..60).toList())
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
                hideLoadingDialog()
                FetchBarangayAddressTask(this@FireLevelActivity, latitude, longitude).execute()
            }
        }
    }

    private fun checkAndSendAlertReport() {
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
                                fireStationName = nearest.name,   // <-- set it here
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

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun handleFetchedAddress(address: String?) {
        if (addressHandled) return
        addressHandled = true
        readableAddress = address
        if (readableAddress != null && readableAddress!!.contains("Tagum City", ignoreCase = true)) {
            Toast.makeText(this, "Location confirmed: $readableAddress", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Address not in Tagum City. You can't submit a report.", Toast.LENGTH_SHORT).show()
            binding.sendButton.isEnabled = false
        }
    }
}
