// ReportSmsActivity.kt
package com.example.flare_capstone

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivitySmsBinding
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ReportSmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var db: AppDatabase

    // Tagum City rough bounding box
    private val TAGUM_LAT_MIN = 7.36
    private val TAGUM_LAT_MAX = 7.56
    private val TAGUM_LNG_MIN = 125.72
    private val TAGUM_LNG_MAX = 125.92

    private val fireStations = listOf(
        FireStation("Canocotan Fire Station", "09673060785", 7.4217617292640785, 125.79018416901866),
        FireStation("Mabini Fire Station", "09663041569", 7.450150854535532, 125.79529166335233),
        FireStation("La Filipina Fire Station", "09750647852", 7.4768350720999655, 125.8054726056261)
    )

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val SMS_PERMISSION_REQUEST_CODE = 101

    companion object {
        const val SMS_SENT_ACTION = "SMS_SENT_ACTION"
        const val EXTRA_TO = "extra_to"
        const val EXTRA_STATION = "extra_station"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }

        registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION), RECEIVER_NOT_EXPORTED)

        binding.logo.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        if (!isSimAvailable()) {
            Toast.makeText(this, "No SIM card detected. Cannot send SMS.", Toast.LENGTH_LONG).show()
        }

        binding.sendReport.setOnClickListener {
            val name = binding.name.text.toString().trim()
            val location = binding.location.text.toString().trim()
            val fireReport = binding.fireReport.text.toString().trim()

            if (name.isEmpty() || location.isEmpty() || fireReport.isEmpty()) {
                Toast.makeText(this, "Complete all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            getCurrentLocation { userLocation ->
                if (userLocation != null) {
                    if (!isWithinTagumCity(userLocation)) {
                        Toast.makeText(this, "Reporting restricted to Tagum City only.", Toast.LENGTH_LONG).show()
                        return@getCurrentLocation
                    }
                    val nearestStation = getNearestFireStation(userLocation)
                    val fullMessage = buildReportMessage(name, location, fireReport, nearestStation.name)
                    confirmSendSms(nearestStation.contact, fullMessage, userLocation, nearestStation.name)
                } else {
                    Toast.makeText(this, "Failed to get location.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val to = intent?.getStringExtra(EXTRA_TO).orEmpty()
            val station = intent?.getStringExtra(EXTRA_STATION).orEmpty()
            when (resultCode) {
                AppCompatActivity.RESULT_OK -> {
                    // no separate Contacts node; number is already embedded in the report details
                    Toast.makeText(applicationContext, "Report SMS sent.", Toast.LENGTH_SHORT).show()
                }
                SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                SmsManager.RESULT_ERROR_NO_SERVICE,
                SmsManager.RESULT_ERROR_NULL_PDU,
                SmsManager.RESULT_ERROR_RADIO_OFF ->
                    Toast.makeText(applicationContext, "Failed to send SMS. Check load/signal.", Toast.LENGTH_LONG).show()
            }
        }
    }

    data class FireStation(val name: String, val contact: String, val latitude: Double, val longitude: Double)

    private fun buildReportMessage(name: String, location: String, fireReport: String, stationName: String): String {
        val (date, time) = getCurrentDateTime()
        return """
            FIRE REPORT SUBMITTED

            NEAREST FIRE STATION:
            $stationName

            NAME:
            $name

            LOCATION:
            $location

            REPORT DETAILS:
            $fireReport

            DATE:
            $date

            TIME:
            $time
        """.trimIndent()
    }

    private fun stationNodeFor(name: String): String? {
        val n = name.trim().lowercase()
        return when {
            "mabini" in n -> "MabiniFireStation"
            "canocotan" in n -> "CanocotanFireStation"
            "la filipina" in n || "lafilipina" in n -> "LaFilipinaFireStation"
            else -> null
        }
    }

    private fun smsChildNodeFor(stationName: String): String {
        val n = stationName.trim().lowercase()
        return when {
            "mabini" in n -> "MabiniSmsReport"
            "canocotan" in n -> "CanocotanSmsReport"
            "la filipina" in n || "lafilipina" in n -> "LaFilipinaSmsReport"
            else -> "SmsReport"
        }
    }

    private fun contactForStationName(stationName: String): String? {
        val target = stationName.trim().lowercase()
        return fireStations.firstOrNull { it.name.trim().lowercase().contains(target) || target.contains(it.name.trim().lowercase()) }?.contact
            ?: when {
                "mabini" in target -> "09750647852"
                "canocotan" in target -> "09663041569"
                "la filipina" in target || "lafilipina" in target -> "09750647852"
                else -> null
            }
    }

    // Push to <StationNode>/<StationSmsChild>/<pushId> and embed the SMS number in the same report details
    private fun uploadPendingReports(db: AppDatabase) {
        val dao = db.reportDao()
        val root = FirebaseDatabase.getInstance().reference

        CoroutineScope(Dispatchers.IO).launch {
            val pendingReports = dao.getPendingReports()
            for (report in pendingReports) {
                val stationNode = stationNodeFor(report.fireStationName)
                val smsChild = smsChildNodeFor(report.fireStationName)
                val contactUsed = contactForStationName(report.fireStationName) ?: ""

                val reportMap = mapOf(
                    "name" to report.name,
                    "location" to report.location,
                    "fireReport" to report.fireReport,
                    "date" to report.date,
                    "time" to report.time,
                    "latitude" to report.latitude,
                    "longitude" to report.longitude,
                    "fireStationName" to report.fireStationName,
                    "contact" to contactUsed,        // embedded here
                    "status" to "pending"
                )

                val ref = if (stationNode != null) {
                    root.child(stationNode).child(smsChild)
                } else {
                    root.child(smsChild)
                }

                ref.push().setValue(reportMap)
                    .addOnSuccessListener {
                        CoroutineScope(Dispatchers.IO).launch { dao.deleteReport(report.id) }
                    }
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    private fun getCurrentDateTime(): Pair<String, String> {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return Pair(sdfDate.format(Date()), sdfTime.format(Date()))
    }

    private fun isWithinTagumCity(loc: Location): Boolean {
        val lat = loc.latitude
        val lng = loc.longitude
        return lat in TAGUM_LAT_MIN..TAGUM_LAT_MAX && lng in TAGUM_LNG_MIN..TAGUM_LNG_MAX
    }

    private fun confirmSendSms(phoneNumber: String, message: String, userLocation: Location, stationName: String) {
        AlertDialog.Builder(this)
            .setTitle("Send Report")
            .setMessage("Send this report via SMS?")
            .setPositiveButton("Yes") { _, _ ->
                val name = binding.name.text.toString().trim()
                val locationText = binding.location.text.toString().trim()
                val fireReport = binding.fireReport.text.toString().trim()
                val (date, time) = getCurrentDateTime()

                val report = SmsReport(
                    name = name,
                    location = locationText,
                    fireReport = fireReport,
                    date = date,
                    time = time,
                    latitude = userLocation.latitude,
                    longitude = userLocation.longitude,
                    fireStationName = stationName
                )

                CoroutineScope(Dispatchers.IO).launch {
                    db.reportDao().insertReport(report)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReportSmsActivity, "Report saved locally (pending).", Toast.LENGTH_SHORT).show()
                        if (isInternetAvailable()) uploadPendingReports(db)
                        sendSms(phoneNumber, message, stationName)
                    }
                }
            }
            .setNegativeButton("No") { d, _ -> d.dismiss() }
            .show()
    }

    private fun sendSms(phoneNumber: String, message: String, stationName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
            return
        }
        try {
            val smsManager = SmsManager.getDefault()

            val sentIntent = Intent(SMS_SENT_ACTION).apply {
                putExtra(EXTRA_TO, phoneNumber)
                putExtra(EXTRA_STATION, stationName)
            }
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val sentPI = PendingIntent.getBroadcast(this, 0, sentIntent, flags)

            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                val sentIntents = MutableList(parts.size) { sentPI }
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts,
                    sentIntents as ArrayList<PendingIntent?>?, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, sentPI, null)
            }

            Toast.makeText(this, "SMS sending…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isSimAvailable(): Boolean {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun checkPermissionsAndGetLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation { }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    callback(location)
                } else {
                    requestLocationUpdates(callback)
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates(callback: (Location?) -> Unit) {
        val req = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { callback(it) }
            }
        }, null)
    }

    private fun getNearestFireStation(userLocation: Location): FireStation {
        var nearest: FireStation? = null
        var shortest = Double.MAX_VALUE
        for (s in fireStations) {
            val stationLoc = Location("").apply { latitude = s.latitude; longitude = s.longitude }
            val dist = userLocation.distanceTo(stationLoc).toDouble()
            if (dist < shortest) { shortest = dist; nearest = s }
        }
        return nearest ?: fireStations.first()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) getCurrentLocation { }
                else Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            SMS_PERMISSION_REQUEST_CODE ->
                Toast.makeText(
                    this,
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) "SMS permission granted" else "SMS permission denied",
                    Toast.LENGTH_SHORT
                ).show()
        }
    }
}
