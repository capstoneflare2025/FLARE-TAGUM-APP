package com.example.flare_capstone

data class ReplyMessage(
    val replyMessage: String = "",
    var imageBase64: String? = null,
    var audioBase64: String = "",    // ✅ NEW: used if storing audio directly in Realtime DB
    val replyDate: String = "1970-01-01",
    val replyTime: String = "00:00:00",
    val timestamp: Long? = 0L,
    var uid: String = "",
    var incidentId: String = "",
    var reporterName: String = ""
)
