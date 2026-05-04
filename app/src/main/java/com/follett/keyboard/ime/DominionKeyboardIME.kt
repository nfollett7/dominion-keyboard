package com.follett.keyboard.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.follett.keyboard.R
import com.follett.keyboard.api.OpenAIClient
import com.follett.keyboard.data.db.KeyboardDatabase
import com.follett.keyboard.data.model.KeystrokeLog
import com.follett.keyboard.data.model.SessionLog
import com.follett.keyboard.utils.PredictiveTextEngine
import com.follett.keyboard.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * DominionKeyboardIME — The core Input Method Service.
 *
 * This service replaces GBoard and provides:
 *  - Full QWERTY keyboard with shift, numbers, symbols
 *  - Predictive text suggestions
 *  - OpenAI Whisper voice dictation
 *  - Spanish translation via GPT-4
 *  - Complete keystroke logging to Room database
 */
class DominionKeyboardIME : InputMethodService() {

    companion object {
        private const val TAG = "DominionKeyboardIME"
        private const val AUDIO_FILE_NAME = "dominion_recording.m4a"
    }

    // ─── Views ───────────────────────────────────────────────────────────────
    private var keyboardView: View? = null
    private var numbersView: View? = null
    private var currentView: KeyboardMode = KeyboardMode.LETTERS

    // ─── State ───────────────────────────────────────────────────────────────
    private var isShifted = false
    private var isCapsLock = false
    private var isRecording = false
    private var isTranslateMode = false
    private var currentWordBuffer = StringBuilder()
    private var sessionId: Long = 0L
    private var lastShiftTapTime = 0L

    // ─── Coroutines ──────────────────────────────────────────────────────────
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // ─── Dependencies ────────────────────────────────────────────────────────
    private lateinit var db: KeyboardDatabase
    private lateinit var openAIClient: OpenAIClient
    private lateinit var predictiveEngine: PredictiveTextEngine
    private lateinit var prefsManager: PrefsManager

    // ─── Audio Recording ─────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    // ─── Suggestion TextViews ─────────────────────────────────────────────────
    private var suggestion1: TextView? = null
    private var suggestion2: TextView? = null
    private var suggestion3: TextView? = null
    private var statusBar: TextView? = null

    // ─── Mic key reference ───────────────────────────────────────────────────
    private var micButton: Button? = null

    enum class KeyboardMode { LETTERS, NUMBERS }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        db = KeyboardDatabase.getInstance(applicationContext)
        openAIClient = OpenAIClient(applicationContext)
        predictiveEngine = PredictiveTextEngine(applicationContext)
        prefsManager = PrefsManager(applicationContext)
        Log.d(TAG, "DominionKeyboardIME created")
    }

    override fun onCreateInputView(): View {
        // Inflate with a FrameLayout parent for proper LayoutParams measurement.
        // Passing null as parent causes the root view's layout_width/height to be
        // ignored, which can result in a zero-height keyboard on some devices.
        val inflater = LayoutInflater.from(this)

        keyboardView = inflater.inflate(R.layout.keyboard_view, null)
        numbersView = inflater.inflate(R.layout.keyboard_numbers, null)

        // Explicitly set layout params to ensure the keyboard has proper dimensions
        // when the IME framework measures and lays out the view.
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        keyboardView!!.layoutParams = params
        numbersView!!.layoutParams = params

        setupLetterKeyboard(keyboardView!!)
        setupNumberKeyboard(numbersView!!)

        return keyboardView!!
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Start a new session log
        serviceScope.launch(Dispatchers.IO) {
            sessionId = db.sessionLogDao().insert(
                SessionLog(
                    startTime = Instant.now().toString(),
                    appPackage = currentInputEditorInfo?.packageName ?: "unknown"
                )
            )
        }
        currentWordBuffer.clear()
        updateSuggestions("")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Close session
        if (sessionId > 0) {
            serviceScope.launch(Dispatchers.IO) {
                db.sessionLogDao().closeSession(sessionId, Instant.now().toString())
            }
        }
        stopRecordingIfActive()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        stopRecordingIfActive()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEYBOARD SETUP
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupLetterKeyboard(view: View) {
        suggestion1 = view.findViewById(R.id.suggestion_1)
        suggestion2 = view.findViewById(R.id.suggestion_2)
        suggestion3 = view.findViewById(R.id.suggestion_3)
        statusBar = view.findViewById(R.id.status_bar)
        micButton = view.findViewById(R.id.key_mic)

        // Attach click listeners to all keys by tag
        attachKeyListeners(view)

        // Suggestion bar taps
        suggestion1?.setOnClickListener { onSuggestionTapped(suggestion1?.text?.toString()) }
        suggestion2?.setOnClickListener { onSuggestionTapped(suggestion2?.text?.toString()) }
        suggestion3?.setOnClickListener { onSuggestionTapped(suggestion3?.text?.toString()) }
    }

    private fun setupNumberKeyboard(view: View) {
        attachKeyListeners(view)
    }

    /**
     * Walk the view hierarchy and attach click listeners to every Button
     * whose tag is set to a key value.
     */
    private fun attachKeyListeners(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachKeyListeners(root.getChildAt(i))
            }
        } else if (root is Button) {
            root.setOnClickListener { v -> onKeyPressed(v as Button) }
            // Long-press delete for continuous deletion
            if (root.tag == "DELETE") {
                root.setOnLongClickListener {
                    clearAllText()
                    true
                }
            }
            // Long-press shift for caps lock
            if (root.tag == "SHIFT") {
                root.setOnLongClickListener {
                    toggleCapsLock()
                    true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY PRESS HANDLING
    // ═════════════════════════════════════════════════════════════════════════

    private fun onKeyPressed(button: Button) {
        val tag = button.tag?.toString() ?: return
        vibrate()

        when (tag) {
            "SHIFT" -> handleShift()
            "DELETE" -> handleDelete()
            "SPACE" -> handleSpace()
            "ENTER" -> handleEnter()
            "NUMBERS" -> switchToNumbers()
            "LETTERS" -> switchToLetters()
            "SYMBOLS2" -> { /* Extended symbols — future expansion */ }
            "MIC" -> handleMicToggle()
            "TRANSLATE" -> handleTranslate()
            else -> handleCharacter(tag)
        }
    }

    private fun handleCharacter(char: String) {
        val ic = currentInputConnection ?: return
        val output = if (isShifted || isCapsLock) char.uppercase() else char.lowercase()
        ic.commitText(output, 1)

        // Log keystroke
        logKeystroke(output, "character")

        // Update word buffer for predictions
        currentWordBuffer.append(output)
        updateSuggestions(currentWordBuffer.toString())

        // Auto-unshift after one capital letter (unless caps lock)
        if (isShifted && !isCapsLock) {
            isShifted = false
            updateShiftState()
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
        logKeystroke(" ", "space")

        // Log completed word
        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            logWord(word)
            predictiveEngine.learnWord(word)
        }
        currentWordBuffer.clear()
        updateSuggestions("")
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (imeAction != null && imeAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(imeAction)
        } else {
            ic.commitText("\n", 1)
        }
        logKeystroke("\n", "enter")

        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            logWord(word)
        }
        currentWordBuffer.clear()
        updateSuggestions("")
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        // Try to delete selected text first, else delete one character
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        logKeystroke("", "delete")

        if (currentWordBuffer.isNotEmpty()) {
            currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
            updateSuggestions(currentWordBuffer.toString())
        }
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 400) {
            toggleCapsLock()
        } else {
            isShifted = !isShifted
            if (isCapsLock) {
                isCapsLock = false
                isShifted = false
            }
            updateShiftState()
        }
        lastShiftTapTime = now
    }

    private fun toggleCapsLock() {
        isCapsLock = !isCapsLock
        isShifted = isCapsLock
        updateShiftState()
    }

    private fun updateShiftState() {
        val view = if (currentView == KeyboardMode.LETTERS) keyboardView else return
        if (view == null) return
        updateKeyLabels(view)
        // Update shift button appearance
        val shiftBtn = view.findViewWithTag<Button>("SHIFT")
        shiftBtn?.alpha = when {
            isCapsLock -> 1.0f
            isShifted -> 0.85f
            else -> 0.6f
        }
        shiftBtn?.text = if (isCapsLock) "⇪" else "⇧"
    }

    private fun updateKeyLabels(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                updateKeyLabels(root.getChildAt(i))
            }
        } else if (root is Button) {
            val tag = root.tag?.toString() ?: return
            if (tag.length == 1 && tag[0].isLetter()) {
                root.text = if (isShifted || isCapsLock) tag.uppercase() else tag.lowercase()
            }
        }
    }

    private fun clearAllText() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        // Select all and delete
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.endBatchEdit()
        currentWordBuffer.clear()
        updateSuggestions("")
    }

    private fun switchToNumbers() {
        currentView = KeyboardMode.NUMBERS
        setInputView(numbersView!!)
    }

    private fun switchToLetters() {
        currentView = KeyboardMode.LETTERS
        setInputView(keyboardView!!)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SUGGESTION TAPPING
    // ═════════════════════════════════════════════════════════════════════════

    private fun onSuggestionTapped(word: String?) {
        if (word.isNullOrBlank()) return
        val ic = currentInputConnection ?: return

        // Delete current partial word
        if (currentWordBuffer.isNotEmpty()) {
            ic.deleteSurroundingText(currentWordBuffer.length, 0)
        }

        // Commit the suggestion + space
        ic.commitText("$word ", 1)
        logWord(word)
        predictiveEngine.learnWord(word)
        currentWordBuffer.clear()
        updateSuggestions("")
    }

    private fun updateSuggestions(prefix: String) {
        serviceScope.launch(Dispatchers.Default) {
            val suggestions = predictiveEngine.getSuggestions(prefix, 3)
            withContext(Dispatchers.Main) {
                suggestion1?.text = suggestions.getOrNull(0) ?: ""
                suggestion2?.text = suggestions.getOrNull(1) ?: ""
                suggestion3?.text = suggestions.getOrNull(2) ?: ""
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VOICE DICTATION (WHISPER)
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleMicToggle() {
        if (isRecording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showToast(getString(R.string.error_no_api_key))
            return
        }

        audioFile = File(cacheDir, AUDIO_FILE_NAME)

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            micButton?.isActivated = true
            micButton?.text = "⏹"
            showStatus("🎤 Listening… speak now")
            logKeystroke("", "mic_start")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            showToast("Microphone error: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecordingAndTranscribe() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }

        isRecording = false
        micButton?.isActivated = false
        micButton?.text = "🎤"
        showStatus("⏳ Processing speech…")

        val file = audioFile ?: return
        if (!file.exists() || file.length() == 0L) {
            hideStatus()
            showToast("No audio captured")
            return
        }

        serviceScope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    openAIClient.transcribeAudio(file)
                }
                if (transcript != null) {
                    val ic = currentInputConnection
                    ic?.commitText(transcript, 1)
                    logKeystroke(transcript, "whisper_dictation")
                    currentWordBuffer.clear()
                    updateSuggestions("")
                }
                hideStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                showToast(getString(R.string.error_network))
                hideStatus()
            }
        }
    }

    private fun stopRecordingIfActive() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder on destroy", e)
            }
            isRecording = false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SPANISH TRANSLATION
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleTranslate() {
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showToast(getString(R.string.error_no_api_key))
            return
        }

        val ic = currentInputConnection ?: return

        // Get the current text before cursor (up to 500 chars)
        val textBeforeCursor = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (textBeforeCursor.isBlank()) {
            showToast("Nothing to translate")
            return
        }

        showStatus("🌐 Translating to Spanish…")

        serviceScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) {
                    openAIClient.translateToSpanish(textBeforeCursor)
                }
                if (translated != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(textBeforeCursor.length, 0)
                    ic.commitText(translated, 1)
                    ic.endBatchEdit()
                    logKeystroke(translated, "translation_es")
                }
                hideStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                showToast(getString(R.string.error_network))
                hideStatus()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DATABASE LOGGING
    // ═════════════════════════════════════════════════════════════════════════

    private fun logKeystroke(value: String, type: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                db.keystrokeLogDao().insert(
                    KeystrokeLog(
                        sessionId = sessionId,
                        keystrokeValue = value,
                        keystrokeType = type,
                        timestamp = Instant.now().toString(),
                        appPackage = currentInputEditorInfo?.packageName ?: "unknown"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log keystroke", e)
            }
        }
    }

    private fun logWord(word: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                db.keystrokeLogDao().insert(
                    KeystrokeLog(
                        sessionId = sessionId,
                        keystrokeValue = word,
                        keystrokeType = "word_complete",
                        timestamp = Instant.now().toString(),
                        appPackage = currentInputEditorInfo?.packageName ?: "unknown"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log word", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun showStatus(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            statusBar?.text = message
            statusBar?.visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        serviceScope.launch(Dispatchers.Main) {
            statusBar?.visibility = View.GONE
        }
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(20)
            }
        } catch (e: Exception) {
            // Vibration is non-critical
        }
    }
}
