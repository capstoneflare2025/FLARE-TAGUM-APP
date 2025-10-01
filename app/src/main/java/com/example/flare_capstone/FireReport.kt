// FireReport.kt
package com.example.flare_capstone

data class FireReport(
    val name: String = "",
    val contact: String = "",
    val fireStartTime: String = "",
    val numberOfHousesAffected: Int = 0,
    val alertLevel: String = "",
    val date: String = "",
    val reportTime: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val location: String = "",
    val exactLocation: String = "",
    val timeStamp: Long = 0L,
    var status: String = "Pending",
    val fireStationName: String = "",
    var read: Boolean = false
)
