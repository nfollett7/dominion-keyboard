package com.follett.keyboard.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SessionLog — Room entity for keyboard usage sessions.
 *
 * A session begins when the keyboard opens in an app and ends
 * when the keyboard is dismissed. Each session groups all keystrokes
 * typed within that context.
 */
@Entity(
    tableName = "session_logs",
    indices = [
        Index("start_time"),
        Index("app_package")
    ]
)
data class SessionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ISO-8601 timestamp when the keyboard session started */
    @ColumnInfo(name = "start_time")
    val startTime: String,

    /** ISO-8601 timestamp when the keyboard session ended (null if still active) */
    @ColumnInfo(name = "end_time")
    val endTime: String? = null,

    /** Package name of the app where typing occurred */
    @ColumnInfo(name = "app_package")
    val appPackage: String = "unknown",

    /** Total number of keystrokes in this session (updated on close) */
    @ColumnInfo(name = "keystroke_count")
    val keystrokeCount: Int = 0,

    /** Total number of words typed in this session */
    @ColumnInfo(name = "word_count")
    val wordCount: Int = 0
)
