package com.example.flare_capstone

/* ----------------------- Data model ----------------------- */
data class EmergencyMedicalServicesReport(
    val type: String = "",
    val name: String = "",
    val contact: String = "",
    val date: String = "",
    val reportTime: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val location: String = "",
    val exactLocation: String = "",
    val timestamp: Long = 0L,
    var status: String = "Pending",
    var read: Boolean = false,
    val fireStationName: String = "Canocotan Fire Station"
)
