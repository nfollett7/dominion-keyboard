package com.follett.keyboard.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.follett.keyboard.data.db.AppUsageStat
import com.follett.keyboard.data.db.KeyboardDatabase
import com.follett.keyboard.data.model.KeystrokeLog
import com.follett.keyboard.data.model.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KeyboardRepository — Single source of truth for all keyboard data.
 *
 * Abstracts database operations from UI layer.
 * Used by ViewModels to access and observe keyboard history.
 */
class KeyboardRepository(context: Context) {

    private val db = KeyboardDatabase.getInstance(context)
    private val keystrokeDao = db.keystrokeLogDao()
    private val sessionDao = db.sessionLogDao()

    // ─── Live Data Streams ────────────────────────────────────────────────────

    val allWordEvents: LiveData<List<KeystrokeLog>> = keystrokeDao.getAllWordEvents()
    val recentWords: LiveData<List<KeystrokeLog>> = keystrokeDao.getRecentWords(200)
    val allSessions: LiveData<List<SessionLog>> = sessionDao.getAllSessions()

    // ─── Search ───────────────────────────────────────────────────────────────

    fun searchWords(query: String): LiveData<List<KeystrokeLog>> =
        keystrokeDao.searchWords(query)

    suspend fun searchLogs(query: String): List<KeystrokeLog> =
        withContext(Dispatchers.IO) { keystrokeDao.searchLogs(query) }

    // ─── Stats ────────────────────────────────────────────────────────────────

    suspend fun getStats(): KeyboardStats = withContext(Dispatchers.IO) {
        KeyboardStats(
            totalKeystrokes = keystrokeDao.getTotalKeystrokeCount(),
            totalWords = keystrokeDao.getTotalWordCount(),
            totalDictations = keystrokeDao.getTotalDictationCount(),
            totalTranslations = keystrokeDao.getTotalTranslationCount(),
            totalSessions = sessionDao.getTotalSessionCount(),
            appUsage = keystrokeDao.getAppUsageStats()
        )
    }

    // ─── Export ───────────────────────────────────────────────────────────────

    suspend fun getAllForExport(): List<KeystrokeLog> =
        withContext(Dispatchers.IO) { keystrokeDao.getAllForExport() }

    // ─── Delete ───────────────────────────────────────────────────────────────

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        keystrokeDao.deleteAll()
        sessionDao.deleteAll()
    }

    suspend fun deleteOlderThan(isoDate: String) = withContext(Dispatchers.IO) {
        keystrokeDao.deleteOlderThan(isoDate)
    }
}

data class KeyboardStats(
    val totalKeystrokes: Long,
    val totalWords: Long,
    val totalDictations: Long,
    val totalTranslations: Long,
    val totalSessions: Long,
    val appUsage: List<AppUsageStat>
)
