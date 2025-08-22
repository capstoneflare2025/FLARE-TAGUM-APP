package com.example.flare_capstone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.databinding.ActivityMyReportBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MyReportActivity : AppCompatActivity(), ReportAdapter.OnItemClickListener {

    private lateinit var binding: ActivityMyReportBinding
    private lateinit var adapter: ReportAdapter
    private val allReports = mutableListOf<Any>()
    private val filteredReports = mutableListOf<Any>()

    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    private var stationsLoaded = 0
    private var slowInternetDetected = false
    private val loadTimeoutMillis = 5000L

    private val handler = Handler(Looper.getMainLooper())
    private val slowInternetRunnable = Runnable {
        if (stationsLoaded < totalStations) {
            slowInternetDetected = true
            showLoadingDialog("Slow internet connection")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (!slowInternetDetected) hideLoadingDialog() }
        }
        override fun onLost(network: Network) {
            runOnUiThread { showLoadingDialog("No internet connection") }
        }
    }

    private val fireReportStations = listOf(
        "LaFilipinaFireStation/LaFilipinaFireReport",
        "CuambuganFireStation/CuambuganFireReport",
        "MabiniFireStation/MabiniFireReport"
    )
    private val otherEmergencyStations = listOf(
        "LaFilipinaFireStation/LaFilipinaOtherEmergency",
        "CuambuganFireStation/CuambuganOtherEmergency",
        "MabiniFireStation/MabiniOtherEmergency"
    )

    private val totalStations = fireReportStations.size + otherEmergencyStations.size
    private var userPhone: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (!isConnected()) {
            showLoadingDialog("No internet connection")
        } else {
            hideLoadingDialog()
            handler.postDelayed(slowInternetRunnable, loadTimeoutMillis)
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        binding.back.setOnClickListener { onBackPressed() }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterReports(newText.orEmpty())
                return true
            }
        })

        binding.reportTypeRadioGroup.check(R.id.allReportsRadioButton)
        binding.reportTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.fireReportRadioButton -> loadUserReports("FireReport")
                R.id.otherEmergencyRadioButton -> loadUserReports("OtherEmergency")
                R.id.allReportsRadioButton -> loadUserReports("All")
            }
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseDatabase.getInstance().getReference("Users").child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                userPhone = snapshot.child("contact").value as? String
                loadUserReports("All") // Ignore empty phone filter for testing
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load user profile.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String) {
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

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        handler.removeCallbacks(slowInternetRunnable)
    }

    private fun loadUserReports(reportType: String) {
        val db = FirebaseDatabase.getInstance().reference
        stationsLoaded = 0
        slowInternetDetected = false
        allReports.clear()

        val stationsToLoad = when (reportType) {
            "FireReport" -> fireReportStations
            "OtherEmergency" -> otherEmergencyStations
            else -> fireReportStations + otherEmergencyStations
        }

        for (path in stationsToLoad) {
            db.child(path).get()
                .addOnSuccessListener { snapshot ->
                    for (reportSnap in snapshot.children) {
                        try {
                            if (path.contains("OtherEmergency")) {
                                val report = OtherEmergency(
                                    emergencyType = reportSnap.child("emergencyType").getValue(String::class.java) ?: "",
                                    name = reportSnap.child("name").getValue(String::class.java) ?: "",
                                    contact = reportSnap.child("contact").getValue(String::class.java) ?: "",
                                    date = reportSnap.child("date").getValue(String::class.java) ?: "",
                                    reportTime = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                    latitude = reportSnap.child("latitude").getValue(String::class.java) ?: "",
                                    longitude = reportSnap.child("longitude").getValue(String::class.java) ?: "",
                                    location = reportSnap.child("location").getValue(String::class.java) ?: "",
                                    exactLocation = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                    lastReportedTime = reportSnap.child("lastReportedTime").getValue(Long::class.java) ?: 0L,
                                    timestamp = reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L,
                                    read = reportSnap.child("read").getValue(Boolean::class.java) ?: false,
                                    fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: ""
                                )
                                allReports.add(report)
                            } else {
                                val latValue = (reportSnap.child("latitude").value as? Number)?.toDouble() ?: 0.0
                                val lonValue = (reportSnap.child("longitude").value as? Number)?.toDouble() ?: 0.0
                                val report = FireReport(
                                    name = reportSnap.child("name").getValue(String::class.java) ?: "",
                                    contact = reportSnap.child("contact").getValue(String::class.java) ?: "",
                                    fireStartTime = reportSnap.child("fireStartTime").getValue(String::class.java) ?: "",
                                    numberOfHousesAffected = reportSnap.child("numberOfHousesAffected").getValue(Int::class.java) ?: 0,
                                    alertLevel = reportSnap.child("alertLevel").getValue(String::class.java) ?: "",
                                    date = reportSnap.child("date").getValue(String::class.java) ?: "",
                                    reportTime = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                    latitude = latValue,
                                    longitude = lonValue,
                                    location = reportSnap.child("location").getValue(String::class.java) ?: "",
                                    exactLocation = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                    timeStamp = reportSnap.child("timeStamp").getValue(Long::class.java) ?: 0L,
                                    status = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                    fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: ""
                                )
                                allReports.add(report)
                            }
                        } catch (e: Exception) {
                            Log.e("ReportParseError", "Failed to parse: ${e.message}")
                        }
                    }
                    stationsLoaded++
                    if (stationsLoaded == stationsToLoad.size) onAllStationsLoaded()
                }
                .addOnFailureListener {
                    stationsLoaded++
                    if (stationsLoaded == stationsToLoad.size) onAllStationsLoaded()
                }
        }
    }

    private fun onAllStationsLoaded() {
        handler.removeCallbacks(slowInternetRunnable)
        if (!slowInternetDetected) hideLoadingDialog()

        allReports.sortByDescending {
            when (it) {
                is FireReport -> it.timeStamp
                is OtherEmergency -> it.timestamp
                else -> 0L
            }
        }
        filteredReports.clear()
        filteredReports.addAll(allReports)
        adapter = ReportAdapter(filteredReports, this)
        binding.reportsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.reportsRecyclerView.adapter = adapter

        if (allReports.isEmpty()) {
            Toast.makeText(this, "No reports found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterReports(query: String) {
        val filtered = allReports.filter {
            when (it) {
                is FireReport -> it.name.contains(query, true) ||
                        it.alertLevel.contains(query, true) ||
                        it.status.contains(query, true)
                is OtherEmergency -> it.name.contains(query, true) ||
                        it.emergencyType.contains(query, true) ||
                        it.fireStationName.contains(query, true)
                else -> false
            }
        }
        filteredReports.clear()
        filteredReports.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    override fun onFireReportClick(report: FireReport) {
        FireReportDialogFragment(report).show(supportFragmentManager, "fireReportDialog")
    }

    override fun onOtherEmergencyClick(report: OtherEmergency) {
        OtherEmergencyDialogFragment(report).show(supportFragmentManager, "otherEmergencyDialog")
    }
}
