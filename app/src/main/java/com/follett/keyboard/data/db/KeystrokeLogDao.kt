package com.follett.keyboard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.follett.keyboard.data.model.KeystrokeLog

/**
 * KeystrokeLogDao — Room DAO for all keystroke log operations.
 */
@Dao
interface KeystrokeLogDao {

    // ─── Insert ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: KeystrokeLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<KeystrokeLog>)

    // ─── Queries — All Logs ───────────────────────────────────────────────────

    @Query("SELECT * FROM keystroke_logs ORDER BY timestamp DESC")
    fun getAllLogs(): LiveData<List<KeystrokeLog>>

    @Query("SELECT * FROM keystroke_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 100): List<KeystrokeLog>

    @Query("SELECT * FROM keystroke_logs WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getLogsForSession(sessionId: Long): List<KeystrokeLog>

    // ─── Queries — Words Only ─────────────────────────────────────────────────

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE keystroke_type = 'word_complete' 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    fun getRecentWords(limit: Int = 200): LiveData<List<KeystrokeLog>>

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE keystroke_type IN ('word_complete', 'whisper_dictation', 'translation_es')
        ORDER BY timestamp DESC
    """)
    fun getAllWordEvents(): LiveData<List<KeystrokeLog>>

    // ─── Queries — Search ─────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE keystroke_value LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT 500
    """)
    suspend fun searchLogs(query: String): List<KeystrokeLog>

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE keystroke_value LIKE '%' || :query || '%'
        AND keystroke_type IN ('word_complete', 'whisper_dictation', 'translation_es')
        ORDER BY timestamp DESC
    """)
    fun searchWords(query: String): LiveData<List<KeystrokeLog>>

    // ─── Queries — By App ─────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE app_package = :packageName 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getLogsByApp(packageName: String, limit: Int = 200): List<KeystrokeLog>

    @Query("""
        SELECT app_package, COUNT(*) as count 
        FROM keystroke_logs 
        GROUP BY app_package 
        ORDER BY count DESC
    """)
    suspend fun getAppUsageStats(): List<AppUsageStat>

    // ─── Queries — Stats ──────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM keystroke_logs")
    suspend fun getTotalKeystrokeCount(): Long

    @Query("SELECT COUNT(*) FROM keystroke_logs WHERE keystroke_type = 'word_complete'")
    suspend fun getTotalWordCount(): Long

    @Query("SELECT COUNT(*) FROM keystroke_logs WHERE keystroke_type = 'whisper_dictation'")
    suspend fun getTotalDictationCount(): Long

    @Query("SELECT COUNT(*) FROM keystroke_logs WHERE keystroke_type = 'translation_es'")
    suspend fun getTotalTranslationCount(): Long

    @Query("""
        SELECT COUNT(*) FROM keystroke_logs 
        WHERE timestamp >= :startDate AND timestamp <= :endDate
    """)
    suspend fun getKeystrokeCountInRange(startDate: String, endDate: String): Long

    // ─── Delete ───────────────────────────────────────────────────────────────

    @Query("DELETE FROM keystroke_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM keystroke_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        DELETE FROM keystroke_logs 
        WHERE timestamp < :beforeDate
    """)
    suspend fun deleteOlderThan(beforeDate: String)

    // ─── Export ───────────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM keystroke_logs 
        WHERE keystroke_type IN ('word_complete', 'whisper_dictation', 'translation_es')
        ORDER BY timestamp ASC
    """)
    suspend fun getAllForExport(): List<KeystrokeLog>
}

/** Lightweight projection for app usage statistics */
data class AppUsageStat(
    val app_package: String,
    val count: Int
)
