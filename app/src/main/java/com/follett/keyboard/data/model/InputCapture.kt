package com.follett.keyboard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * InputCapture — Full message capture for the digital twin.
 *
 * This is NOT keystroke logging. This captures complete composed messages
 * with full context for building the user's digital twin over time.
 *
 * What this enables:
 *  - Communication style analysis (formality, vocabulary, tone)
 *  - Temporal patterns (when the user types, peak hours, session duration)
 *  - Topic clustering (what subjects they write about most)
 *  - App usage patterns (which apps they type in most)
 *  - Input method preference (typed vs voice dictated vs translated)
 *  - Recipient inference (messaging patterns without reading content)
 *
 * Privacy:
 *  - NEVER captures from password fields
 *  - NEVER captures from banking/financial apps
 *  - User can disable via Settings toggle
 *  - All data stored locally only (never sent to cloud unless user opts in)
 */
@Entity(tableName = "input_captures")
data class InputCapture(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The complete committed text (full message, not individual characters) */
    val fullText: String,

    /** Which app the user was typing in */
    val appPackage: String,

    /** Detected field type: message, search, email, note, url, unknown */
    val fieldType: String,

    /** Word count of the committed text */
    val wordCount: Int,

    /** ISO-8601 timestamp when the message was committed */
    val timestamp: String,

    /** How long the user spent composing this message (milliseconds) */
    val composeDurationMs: Long,

    /** Whether this was voice-dictated via Whisper */
    val wasVoiceDictated: Boolean = false,

    /** Whether this was translated */
    val wasTranslated: Boolean = false,

    /** Detected language (en, es, etc.) */
    val languageDetected: String = "en",

    /** Session ID for grouping captures within a typing session */
    val sessionId: Long = 0
)
