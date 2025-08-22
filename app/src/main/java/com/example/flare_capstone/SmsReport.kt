package com.example.flare_capstone

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_reports")
data class SmsReport(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val location: String,
    val fireReport: String,
    val date: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val fireStationName: String,          // <-- added
    val status: String = "pending"
)
