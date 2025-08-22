// MainActivity.kt
package com.example.flare_capstone

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityMainBinding
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(applicationContext)

        if (isInternetAvailable()) uploadPendingReports()

        binding.loginButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java)); finish()
        }

        binding.stationContact.setOnClickListener {
            startActivity(Intent(this, FireStationInfoActivity::class.java).putExtra("fromReport", true))
        }

        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java)); finish()
        }

        binding.logo.setOnClickListener {
            startActivity(Intent(this, Onboard1Activity::class.java)); finish()
        }

        binding.smsReport.setOnClickListener {
            startActivity(Intent(this, ReportSmsActivity::class.java)); finish()
        }

        binding.onboard.setOnClickListener {
            startActivity(Intent(this, Onboard1Activity::class.java)); finish()
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    // Map display name -> station node only; child is "SmsReport"
    private fun stationNodeFor(name: String): String? {
        val n = name.trim().lowercase()
        return when {
            "mabini" in n        -> "MabiniFireStation"
            "canocotan" in n     -> "CanocotanFireStation"
            "la filipina" in n ||
                    "lafilipina" in n    -> "LaFilipinaFireStation"
            else -> null
        }
    }

    // Push pending SmsReport under <StationNode>/SmsReport
    private fun uploadPendingReports() {
        val dao = db.reportDao()
        val dbRef = FirebaseDatabase.getInstance().reference

        CoroutineScope(Dispatchers.IO).launch {
            val pending = dao.getPendingReports()
            for (report in pending) {
                val stationNode = stationNodeFor(report.fireStationName)

                val reportMap = mapOf(
                    "name" to report.name,
                    "location" to report.location,
                    "fireReport" to report.fireReport,
                    "date" to report.date,
                    "time" to report.time,
                    "latitude" to report.latitude,
                    "longitude" to report.longitude,
                    "fireStationName" to report.fireStationName,
                    "status" to "sent"
                )

                val task = if (stationNode != null) {
                    dbRef.child(stationNode).child("SmsReport").push().setValue(reportMap)
                } else {
                    dbRef.child("SmsReport").push().setValue(reportMap)
                }

                task
                    .addOnSuccessListener {
                        CoroutineScope(Dispatchers.IO).launch { dao.deleteReport(report.id) }
                    }
                    .addOnFailureListener {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to sync report: ${report.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }
}
