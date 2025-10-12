package com.example.flare_capstone

data class OtherEmergency(
    val emergencyType: String,
    val name: String,
    val contact: String,
    val date: String,
    val reportTime: String,
    val latitude: String,
    val longitude: String,
    val location: String,
    val exactLocation: String = "",
    val lastReportedTime: Long,
    val timestamp: Long,
    var status: String = "Pending",
    var read: Boolean,
    val fireStationName: String = "", // <-- new field
    val type: String = "",
    val category: String = "OTHER"    // OTHER / EMS / SMS
)
