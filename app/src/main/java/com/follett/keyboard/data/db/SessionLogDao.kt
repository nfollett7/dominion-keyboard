package com.follett.keyboard.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.follett.keyboard.data.model.SessionLog

/**
 * SessionLogDao — Room DAO for keyboard session management.
 */
@Dao
interface SessionLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionLog): Long

    @Query("SELECT * FROM session_logs ORDER BY start_time DESC")
    fun getAllSessions(): LiveData<List<SessionLog>>

    @Query("SELECT * FROM session_logs ORDER BY start_time DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 50): List<SessionLog>

    @Query("SELECT * FROM session_logs WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionLog?

    @Query("""
        UPDATE session_logs 
        SET end_time = :endTime 
        WHERE id = :sessionId
    """)
    suspend fun closeSession(sessionId: Long, endTime: String)

    @Query("""
        UPDATE session_logs 
        SET keystroke_count = :count 
        WHERE id = :sessionId
    """)
    suspend fun updateKeystrokeCount(sessionId: Long, count: Int)

    @Query("SELECT COUNT(*) FROM session_logs")
    suspend fun getTotalSessionCount(): Long

    @Query("DELETE FROM session_logs")
    suspend fun deleteAll()
}
