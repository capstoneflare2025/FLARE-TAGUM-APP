package com.example.flare_capstone

data class ResponseMessage(
    var uid: String = "",
    var stationNode: String? = null,              // <-- carry the exact node (e.g., "LaFilipinaFireStation")
    val fireStationName: String? = null,
    val incidentId: String? = null,
    val reporterName: String? = null,
    val contact: String? = null,
    val responseMessage: String? = null,
    val responseDate: String = "1970-01-01",
    val responseTime: String = "00:00:00",
    var imageBase64: String? = null,
    val timestamp: Long? = 0L,
    var isRead: Boolean = false,
    var category: String? = null // "fire" / "other"
) {
    fun getIsRead(): Boolean = isRead
    fun setIsRead(isRead: Boolean) { this.isRead = isRead }
}
