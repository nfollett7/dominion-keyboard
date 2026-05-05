package com.follett.keyboard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.follett.keyboard.data.model.InputCapture

/**
 * InputCaptureDao — Data access for the Input Intelligence System.
 *
 * Provides queries for:
 *  - Inserting new captures
 *  - Analyzing communication patterns
 *  - Retrieving recent context for the digital twin
 */
@Dao
interface InputCaptureDao {

    @Insert
    suspend fun insert(capture: InputCapture): Long

    @Insert
    suspend fun insertAll(captures: List<InputCapture>)

    /** Get recent captures for context (last N messages) */
    @Query("SELECT * FROM input_captures ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<InputCapture>

    /** Get captures by app for pattern analysis */
    @Query("SELECT * FROM input_captures WHERE appPackage = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByApp(pkg: String, limit: Int = 50): List<InputCapture>

    /** Get total message count */
    @Query("SELECT COUNT(*) FROM input_captures")
    suspend fun getTotalCount(): Long

    /** Get average compose duration */
    @Query("SELECT AVG(composeDurationMs) FROM input_captures WHERE composeDurationMs > 0")
    suspend fun getAverageComposeDuration(): Double?

    /** Get most-used apps */
    @Query("SELECT appPackage as app_package, COUNT(*) as count FROM input_captures GROUP BY appPackage ORDER BY count DESC LIMIT :limit")
    suspend fun getMostUsedApps(limit: Int = 10): List<AppUsageStat>

    /** Get captures from a time range for temporal analysis */
    @Query("SELECT * FROM input_captures WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp")
    suspend fun getInTimeRange(startTime: String, endTime: String): List<InputCapture>

    /** Get voice-dictated captures */
    @Query("SELECT * FROM input_captures WHERE wasVoiceDictated = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getVoiceDictated(limit: Int = 50): List<InputCapture>

    /** Delete captures older than a timestamp (for data hygiene) */
    @Query("DELETE FROM input_captures WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: String): Int

    /** Get word count statistics */
    @Query("SELECT SUM(wordCount) FROM input_captures")
    suspend fun getTotalWordCount(): Long?
}

// AppUsageStat is defined in KeystrokeLogDao.kt
