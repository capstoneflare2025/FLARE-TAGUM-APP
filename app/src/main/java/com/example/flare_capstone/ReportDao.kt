package com.example.flare_capstone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportDao {

    // Create / Upsert
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: SmsReport)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReports(reports: List<SmsReport>)

    // Read
    @Query("SELECT * FROM pending_reports WHERE status = 'pending'")
    suspend fun getPendingReports(): List<SmsReport>

    @Query("SELECT * FROM pending_reports ORDER BY id DESC")
    suspend fun getAllReports(): List<SmsReport>

    @Query("SELECT COUNT(*) FROM pending_reports WHERE status = 'pending'")
    suspend fun countPending(): Int

    // Update
    @Query("UPDATE pending_reports SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("UPDATE pending_reports SET fireStationName = :stationName WHERE id = :id")
    suspend fun updateFireStationName(id: Int, stationName: String)

    // Delete
    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun deleteReport(id: Int)

    @Query("DELETE FROM pending_reports WHERE status = 'sent'")
    suspend fun deleteAllSent()
}
