package com.follett.keyboard.ime

import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.follett.keyboard.data.db.InputCaptureDao
import com.follett.keyboard.data.model.InputCapture
import java.time.Instant

/**
 * InputIntelligenceEngine — Full message capture for the digital twin.
 *
 * This engine captures COMPLETE composed messages (not keystrokes) with
 * full context. It's the foundation for the agentic system's understanding
 * of who the user is, how they communicate, and what they care about.
 *
 * Capture triggers:
 *  - When user sends a message (long-press Enter / app action)
 *  - When user leaves a text field after typing
 *  - When a voice dictation is committed
 *  - When a translation is committed
 *
 * Privacy rules:
 *  - NEVER captures from password fields
 *  - NEVER captures from sensitive apps (banking, auth)
 *  - Minimum 3 words to capture (ignores single-word searches)
 *  - User can disable entirely via Settings
 */
class InputIntelligenceEngine(private val dao: InputCaptureDao?) {

    companion object {
        private const val TAG = "InputIntelligence"
        private const val MIN_WORDS_TO_CAPTURE = 3
        private val SENSITIVE_PACKAGES = setOf(
            "com.chase.sig.android",
            "com.bankofamerica.cashpromobile",
            "com.wf.wellsfargomobile",
            "com.venmo",
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "org.thoughtcrime.securesms"  // Signal
        )
    }

    private var sessionStartTime: Long = 0L
    private var currentSessionId: Long = 0L

    /**
     * Start tracking a new input session.
     */
    fun startSession(sessionId: Long) {
        currentSessionId = sessionId
        sessionStartTime = System.currentTimeMillis()
    }

    /**
     * Capture a completed message.
     *
     * @param fullText The complete text that was committed
     * @param editorInfo The current field's editor info
     * @param wasVoice Whether this was voice-dictated
     * @param wasTranslated Whether this was translated
     */
    suspend fun captureMessage(
        fullText: String,
        editorInfo: EditorInfo?,
        wasVoice: Boolean = false,
        wasTranslated: Boolean = false
    ) {
        if (dao == null) return
        if (fullText.isBlank()) return

        val pkg = editorInfo?.packageName ?: "unknown"

        // Privacy checks
        if (isSensitiveApp(pkg)) return
        if (isPasswordField(editorInfo)) return

        val wordCount = fullText.trim().split("\\s+".toRegex()).size
        if (wordCount < MIN_WORDS_TO_CAPTURE && !wasVoice) return

        val composeDuration = System.currentTimeMillis() - sessionStartTime
        val fieldType = detectFieldType(editorInfo)

        try {
            dao.insert(InputCapture(
                fullText = fullText.trim(),
                appPackage = pkg,
                fieldType = fieldType,
                wordCount = wordCount,
                timestamp = Instant.now().toString(),
                composeDurationMs = composeDuration,
                wasVoiceDictated = wasVoice,
                wasTranslated = wasTranslated,
                languageDetected = detectLanguage(fullText),
                sessionId = currentSessionId
            ))
            Log.d(TAG, "Captured: ${wordCount}w from $pkg ($fieldType)")
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }

        // Reset timer for next message
        sessionStartTime = System.currentTimeMillis()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DETECTION HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun isSensitiveApp(pkg: String): Boolean {
        return pkg in SENSITIVE_PACKAGES || pkg.contains("bank") || pkg.contains("finance")
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun detectFieldType(info: EditorInfo?): String {
        val inputType = info?.inputType ?: return "unknown"
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        return when {
            info.packageName?.contains("messaging") == true -> "message"
            info.packageName?.contains("sms") == true -> "message"
            info.packageName?.contains("chat") == true -> "message"
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "email"
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email"
            variation == InputType.TYPE_TEXT_VARIATION_URI -> "url"
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "web"
            variation == InputType.TYPE_TEXT_VARIATION_FILTER -> "search"
            info.imeOptions and EditorInfo.IME_ACTION_SEARCH != 0 -> "search"
            variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "note"
            else -> "unknown"
        }
    }

    private fun detectLanguage(text: String): String {
        // Simple heuristic: check for Spanish characters/patterns
        val spanishIndicators = listOf("ñ", "¿", "¡", "á", "é", "í", "ó", "ú")
        val hasSpanish = spanishIndicators.any { text.contains(it) }
        return if (hasSpanish) "es" else "en"
    }
}
