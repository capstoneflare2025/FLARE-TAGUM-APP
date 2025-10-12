package com.example.flare_capstone

data class FireFighterStation(
    val id: String = "",
    val name: String = "",
    val lastMessage: String = "",
    val timestamp: Long = 0L,
    val profileUrl: String = "",
    val lastSender: String = "" // 👈 new field
)
