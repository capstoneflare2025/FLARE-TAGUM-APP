package com.example.flare_capstone

data class ChatMessage(
    val sender: String = "",
    val text: String? = null,        // present only for text messages
    val imageUrl: String? = null,    // present only for image messages
    val audioUrl: String? = null,    // present only for audio messages
    val durationMs: Long? = null,    // only with audioUrl
    val timestamp: Long = 0L
)
