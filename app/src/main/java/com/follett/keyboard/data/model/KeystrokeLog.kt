package com.follett.keyboard.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * KeystrokeLog — Room entity for individual keystroke events.
 *
 * Every key press, word completion, voice dictation, and translation
 * is stored here with full metadata for analysis and review.
 */
@Entity(
    tableName = "keystroke_logs",
    foreignKeys = [
        ForeignKey(
            entity = SessionLog::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("session_id"),
        Index("timestamp"),
        Index("keystroke_type"),
        Index("app_package")
    ]
)
data class KeystrokeLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    /**
     * The actual value typed:
     *  - Single character for regular keys
     *  - Full word for word_complete events
     *  - Full transcription for whisper_dictation
     *  - Translated text for translation_es
     *  - Empty string for delete/space/enter
     */
    @ColumnInfo(name = "keystroke_value")
    val keystrokeValue: String,

    /**
     * Type of keystroke event:
     *  - "character"       — Regular letter/number/symbol key
     *  - "space"           — Space bar pressed
     *  - "delete"          — Backspace pressed
     *  - "enter"           — Enter/Return pressed
     *  - "word_complete"   — Full word committed (on space/enter)
     *  - "whisper_dictation" — Voice transcription inserted
     *  - "translation_es"  — Spanish translation inserted
     *  - "mic_start"       — Microphone recording started
     *  - "suggestion"      — Prediction suggestion tapped
     */
    @ColumnInfo(name = "keystroke_type")
    val keystrokeType: String,

    /** ISO-8601 timestamp of when the keystroke occurred */
    @ColumnInfo(name = "timestamp")
    val timestamp: String,

    /** Package name of the app being typed in (e.g., com.whatsapp) */
    @ColumnInfo(name = "app_package")
    val appPackage: String = "unknown"
)
