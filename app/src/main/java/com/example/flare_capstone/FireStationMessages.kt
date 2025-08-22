package com.example.flare_capstone

data class FireStationMessages(
    val name: String = "",
    val contact: String = "",
    val date: String = "",
    val time: String = "",
    val fireStation: String = "",
    val fireStationLatitude: String? = null,
    val fireStationLongitude: String? = null,
    val fireLevel: String? = null,
    val emergencyType: String? = null,
    val message: List<String> = listOf()  // List of messages
)
