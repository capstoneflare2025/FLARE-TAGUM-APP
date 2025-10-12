package com.example.flare_capstone

import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.databinding.ActivityMyReportBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MyReportActivity : AppCompatActivity(), ReportAdapter.OnItemClickListener {

    private lateinit var binding: ActivityMyReportBinding
    private lateinit var adapter: ReportAdapter

    private val allReports = mutableListOf<Any>()
    private val filteredReports = mutableListOf<Any>()

    // ----- Filters -----
    private enum class TypeFilter { ALL, FIRE, OTHER, EMS, SMS }
    private enum class StatusFilter { ALL, PENDING, RESPONDING, RESOLVED }

    // Internal normalized category for filtering
    private enum class Category { FIRE, OTHER, EMS, SMS }

    private var typeFilter: TypeFilter = TypeFilter.ALL
    private var statusFilter: StatusFilter = StatusFilter.ALL
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null
    private var searchQuery: String = ""

    // ----- DB paths -----
    private val STATION_ROOT = "TagumCityCentralFireStation"
    private val FIRE_PATH    = "$STATION_ROOT/AllReport/FireReport"
    private val OTHER_PATH   = "$STATION_ROOT/AllReport/OtherEmergencyReport"
    private val EMS_PATH     = "$STATION_ROOT/AllReport/EmergencyMedicalServicesReport"
    private val SMS_PATH     = "$STATION_ROOT/AllReport/SmsReport"

    // ----- Current user -----
    private var userName: String? = null
    private var userContact: String? = null
    private var userEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = ReportAdapter(filteredReports, this)
        binding.reportsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.reportsRecyclerView.adapter = adapter

        initFiltersUI()

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                applyFilters()
                return true
            }
        })

        val authUser = FirebaseAuth.getInstance().currentUser
        if (authUser == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        userEmail = authUser.email

        FirebaseDatabase.getInstance().getReference("Users")
            .child(authUser.uid)
            .get()
            .addOnSuccessListener { snap ->
                userName = snap.child("name").getValue(String::class.java)?.trim()
                userContact = snap.child("contact").getValue(String::class.java)?.trim()
                Log.d("MyReport", "Profile → name=[$userName], contact=[$userContact], email=[$userEmail]")
                loadOnlyCurrentUsersReports()
            }
            .addOnFailureListener {
                Log.w("MyReport", "Failed to load profile; continuing with email only.")
                loadOnlyCurrentUsersReports()
            }
    }

    private fun initFiltersUI() {
        val typeItems = listOf("All", "Fire Report", "Other Emergency", "EMS", "SMS")
        binding.typeDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, typeItems))
        binding.typeDropdown.setText("All", false)
        binding.typeDropdown.setOnItemClickListener { _, _, pos, _ ->
            typeFilter = when (pos) {
                1 -> TypeFilter.FIRE
                2 -> TypeFilter.OTHER
                3 -> TypeFilter.EMS
                4 -> TypeFilter.SMS
                else -> TypeFilter.ALL
            }
            applyFilters()
        }

        // EXACT strings you wanted
        val statusItems = listOf("All", "Pending", "Ongoing", "Completed")
        binding.statusDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, statusItems))
        binding.statusDropdown.setText("All", false)
        binding.statusDropdown.setOnItemClickListener { _, _, pos, _ ->
            statusFilter = when (pos) {
                1 -> StatusFilter.PENDING
                2 -> StatusFilter.RESPONDING   // "Ongoing"
                3 -> StatusFilter.RESOLVED     // "Completed"
                else -> StatusFilter.ALL
            }
            applyFilters()
        }

        binding.dateRangeBtn.setOnClickListener {
            val picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Select date range")
                .build()

            picker.addOnPositiveButtonClickListener { range ->
                dateFromMillis = range.first
                dateToMillis = range.second?.plus(86_400_000L - 1)
                binding.dateRangeBtn.text = picker.headerText
                applyFilters()
            }
            picker.show(supportFragmentManager, "dateRangePicker")
        }

        binding.dateRangeBtn.setOnLongClickListener {
            dateFromMillis = null
            dateToMillis = null
            binding.dateRangeBtn.text = "Date range"
            applyFilters()
            true
        }
    }

    private fun normalizePhone(s: String?): String = s?.filter { it.isDigit() } ?: ""

    private fun belongsToCurrentUser(name: String?, contact: String?): Boolean {
        val userContactN = normalizePhone(userContact)
        val reportContactN = normalizePhone(contact)
        val contactMatches = userContactN.isNotEmpty() && reportContactN.isNotEmpty() && userContactN == reportContactN

        val userNameN = (userName ?: "").trim().lowercase()
        val reportNameN = (name ?: "").trim().lowercase()
        val nameMatches = userNameN.isNotEmpty() && reportNameN.isNotEmpty() && userNameN == reportNameN

        return contactMatches || nameMatches
    }

    private fun typeStringOf(r: Any): String = when (r) {
        is FireReport     -> r.type.ifBlank { "Fire" }
        is OtherEmergency -> r.emergencyType.ifBlank { "Other" }
        else              -> ""
    }

    private fun categoryOf(r: Any): Category = when (r) {
        is FireReport     -> Category.FIRE
        is OtherEmergency -> when {
            r.category.equals("EMS", true) -> Category.EMS
            r.category.equals("SMS", true) -> Category.SMS
            else                           -> Category.OTHER
        }
        else -> Category.OTHER
    }

    private fun loadOnlyCurrentUsersReports() {
        allReports.clear()
        filteredReports.clear()
        adapter.notifyDataSetChanged()

        val db = FirebaseDatabase.getInstance().reference
        val paths = listOf(FIRE_PATH, OTHER_PATH, EMS_PATH, SMS_PATH)
        var finished = 0

        paths.forEach { path ->
            db.child(path).get()
                .addOnSuccessListener { snapshot ->
                    for (reportSnap in snapshot.children) {
                        try {
                            when {
                                path.endsWith("FireReport") -> {
                                    val lat = (reportSnap.child("latitude").value as? Number)?.toDouble() ?: 0.0
                                    val lon = (reportSnap.child("longitude").value as? Number)?.toDouble() ?: 0.0
                                    val name = reportSnap.child("name").getValue(String::class.java)
                                    val contact = reportSnap.child("contact").getValue(String::class.java)
                                    if (!belongsToCurrentUser(name, contact)) continue

                                    val report = FireReport(
                                        name            = name ?: "",
                                        contact         = contact ?: "",
                                        date            = reportSnap.child("date").getValue(String::class.java) ?: "",
                                        reportTime      = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                        latitude        = lat,
                                        longitude       = lon,
                                        exactLocation   = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                        timeStamp       = reportSnap.child("timeStamp").getValue(Long::class.java)
                                            ?: (reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L),
                                        status          = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                        fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                        type            = reportSnap.child("type").getValue(String::class.java) ?: "",
                                        category        = "FIRE"
                                    )
                                    allReports.add(report)
                                }

                                path.endsWith("OtherEmergencyReport") -> {
                                    val name = reportSnap.child("name").getValue(String::class.java)
                                    val contact = reportSnap.child("contact").getValue(String::class.java)
                                    if (!belongsToCurrentUser(name, contact)) continue

                                    val report = OtherEmergency(
                                        emergencyType   = reportSnap.child("emergencyType").getValue(String::class.java) ?: "Other",
                                        name            = name ?: "",
                                        contact         = contact ?: "",
                                        date            = reportSnap.child("date").getValue(String::class.java) ?: "",
                                        reportTime      = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                        latitude        = reportSnap.child("latitude").getValue(String::class.java) ?: "",
                                        longitude       = reportSnap.child("longitude").getValue(String::class.java) ?: "",
                                        location        = reportSnap.child("location").getValue(String::class.java) ?: "",
                                        exactLocation   = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                        lastReportedTime= reportSnap.child("lastReportedTime").getValue(Long::class.java) ?: 0L,
                                        timestamp       = reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L,
                                        read            = reportSnap.child("read").getValue(Boolean::class.java) ?: false,
                                        fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                        status          = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                        type            = reportSnap.child("type").getValue(String::class.java) ?: "",
                                        category        = "OTHER"
                                    )
                                    allReports.add(report)
                                }

                                path.endsWith("EmergencyMedicalServicesReport") -> {
                                    val name = reportSnap.child("name").getValue(String::class.java)
                                    val contact = reportSnap.child("contact").getValue(String::class.java)
                                    if (!belongsToCurrentUser(name, contact)) continue

                                    val emsType = reportSnap.child("type").getValue(String::class.java) ?: "EMS"
                                    val report = OtherEmergency(
                                        emergencyType   = emsType, // show EMS subtype (e.g. “Seizure”)
                                        name            = name ?: "",
                                        contact         = contact ?: "",
                                        date            = reportSnap.child("date").getValue(String::class.java) ?: "",
                                        reportTime      = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                        latitude        = reportSnap.child("latitude").getValue(String::class.java) ?: "",
                                        longitude       = reportSnap.child("longitude").getValue(String::class.java) ?: "",
                                        location        = reportSnap.child("location").getValue(String::class.java) ?: "",
                                        exactLocation   = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                        lastReportedTime= reportSnap.child("lastReportedTime").getValue(Long::class.java) ?: 0L,
                                        timestamp       = reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L,
                                        read            = reportSnap.child("read").getValue(Boolean::class.java) ?: false,
                                        fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                        status          = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                        type            = emsType,
                                        category        = "EMS"
                                    )
                                    allReports.add(report)
                                }

                                path.endsWith("SmsReport") -> {
                                    val name = reportSnap.child("name").getValue(String::class.java)
                                    val contact = reportSnap.child("contact").getValue(String::class.java)
                                    if (!belongsToCurrentUser(name, contact)) continue

                                    val msg = reportSnap.child("message").getValue(String::class.java) ?: ""
                                    val smsType = reportSnap.child("type").getValue(String::class.java) ?: "SMS"
                                    val report = OtherEmergency(
                                        emergencyType   = smsType,
                                        name            = name ?: "",
                                        contact         = contact ?: "",
                                        date            = reportSnap.child("date").getValue(String::class.java) ?: "",
                                        reportTime      = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                        latitude        = "",
                                        longitude       = "",
                                        location        = msg,
                                        exactLocation   = "",
                                        lastReportedTime= 0L,
                                        timestamp       = reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L,
                                        read            = reportSnap.child("read").getValue(Boolean::class.java) ?: false,
                                        fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                        status          = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                        type            = smsType,
                                        category        = "SMS"
                                    )
                                    allReports.add(report)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ReportParseError", "Failed to parse: ${e.message}")
                        }
                    }
                }
                .addOnCompleteListener {
                    finished++
                    if (finished == paths.size) onAllLoaded()
                }
        }
    }

    private fun onAllLoaded() {
        allReports.sortByDescending {
            when (it) {
                is FireReport     -> it.timeStamp
                is OtherEmergency -> it.timestamp
                else              -> 0L
            }
        }
        applyFilters()

        if (allReports.isEmpty()) {
            Toast.makeText(this, "No reports found for your account.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFilters() {
        val q = searchQuery.trim()

        val filtered = allReports.asSequence()
            // Type filter (EXHAUSTIVE and returns Boolean)
            .filter { r ->
                val cat = categoryOf(r)
                when (typeFilter) {
                    TypeFilter.ALL   -> true
                    TypeFilter.FIRE  -> cat == Category.FIRE
                    TypeFilter.OTHER -> cat == Category.OTHER
                    TypeFilter.EMS   -> cat == Category.EMS
                    TypeFilter.SMS   -> cat == Category.SMS
                }
            }
            // Status filter (FireReport only) — exact strings
            .filter { r ->
                when (statusFilter) {
                    StatusFilter.ALL        -> true
                    StatusFilter.PENDING    -> (r as? FireReport)?.status.equals("Pending", true)
                    StatusFilter.RESPONDING -> (r as? FireReport)?.status.equals("Ongoing", true)
                    StatusFilter.RESOLVED   -> (r as? FireReport)?.status.equals("Completed", true)
                }
            }
            // Date range filter
            .filter { r ->
                val ts = when (r) {
                    is FireReport     -> r.timeStamp
                    is OtherEmergency -> r.timestamp
                    else              -> 0L
                }
                val afterStart = dateFromMillis?.let { ts >= it } ?: true
                val beforeEnd  = dateToMillis?.let { ts <= it } ?: true
                afterStart && beforeEnd
            }
            // Search (includes real type strings)
            .filter { r ->
                if (q.isEmpty()) return@filter true
                val typeStr = typeStringOf(r)
                when (r) {
                    is FireReport -> {
                        r.name.contains(q, true) ||
                                r.status.contains(q, true) ||
                                r.fireStationName.contains(q, true) ||
                                r.exactLocation.contains(q, true) ||
                                typeStr.contains(q, true) ||
                                r.type.contains(q, true)
                    }
                    is OtherEmergency -> {
                        r.name.contains(q, true) ||
                                r.emergencyType.contains(q, true) ||
                                r.fireStationName.contains(q, true) ||
                                r.location.contains(q, true) ||
                                r.exactLocation.contains(q, true) ||
                                typeStr.contains(q, true) ||
                                r.type.contains(q, true)
                    }
                    else -> false
                }
            }
            .toList()

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
