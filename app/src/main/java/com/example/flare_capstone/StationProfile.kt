package com.example.flare_capstone

data class StationProfile(
    val name: String = "",
    val contact: String = "",
    val locationUrl: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val location: String? = null,
    var isRead: Boolean = false
) {
    // Default constructor for Firebase
    constructor() : this("", "", "", 0.0, 0.0)
}



