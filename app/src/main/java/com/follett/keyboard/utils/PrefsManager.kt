package com.follett.keyboard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * PrefsManager
 *
 * Centralized SharedPreferences manager for all app settings.
 * Handles API key storage, keyboard preferences, and feature flags.
 */
class PrefsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "dominion_keyboard_settings"

        // Keys
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        const val KEY_SOUND_FEEDBACK = "sound_feedback"
        const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        const val KEY_AUTO_PUNCTUATE = "auto_punctuate"
        const val KEY_TRANSLATE_MODE = "translate_mode"
        const val KEY_SMART_COMPOSE = "smart_compose"
        const val KEY_LOG_KEYSTROKES = "log_keystrokes"
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_TOTAL_KEYSTROKES = "total_keystrokes"
        const val KEY_TOTAL_WORDS = "total_words"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── API Key ─────────────────────────────────────────────────────────────

    fun getApiKey(): String? = prefs.getString(KEY_OPENAI_API_KEY, null)

    fun setApiKey(key: String) = prefs.edit { putString(KEY_OPENAI_API_KEY, key.trim()) }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    // ─── Keyboard Preferences ─────────────────────────────────────────────────

    fun isHapticEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
    fun setHapticEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_HAPTIC_FEEDBACK, enabled) }

    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND_FEEDBACK, false)
    fun setSoundEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_SOUND_FEEDBACK, enabled) }

    fun isAutoCapitalizeEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CAPITALIZE, true)
    fun setAutoCapitalize(enabled: Boolean) = prefs.edit { putBoolean(KEY_AUTO_CAPITALIZE, enabled) }

    fun isAutoPunctuateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_PUNCTUATE, false)
    fun setAutoPunctuate(enabled: Boolean) = prefs.edit { putBoolean(KEY_AUTO_PUNCTUATE, enabled) }

    fun isSmartComposeEnabled(): Boolean = prefs.getBoolean(KEY_SMART_COMPOSE, false)
    fun setSmartCompose(enabled: Boolean) = prefs.edit { putBoolean(KEY_SMART_COMPOSE, enabled) }

    fun isKeystrokeLoggingEnabled(): Boolean = prefs.getBoolean(KEY_LOG_KEYSTROKES, true)
    fun setKeystrokeLogging(enabled: Boolean) = prefs.edit { putBoolean(KEY_LOG_KEYSTROKES, enabled) }

    // ─── Setup State ──────────────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    fun setSetupComplete(complete: Boolean) = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, complete) }

    // ─── Stats ────────────────────────────────────────────────────────────────

    fun getTotalKeystrokes(): Long = prefs.getLong(KEY_TOTAL_KEYSTROKES, 0L)
    fun incrementKeystrokes() = prefs.edit {
        putLong(KEY_TOTAL_KEYSTROKES, getTotalKeystrokes() + 1)
    }

    fun getTotalWords(): Long = prefs.getLong(KEY_TOTAL_WORDS, 0L)
    fun incrementWords() = prefs.edit {
        putLong(KEY_TOTAL_WORDS, getTotalWords() + 1)
    }

    // ─── Clear ────────────────────────────────────────────────────────────────

    fun clearAll() = prefs.edit { clear() }
}
