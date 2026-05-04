package com.follett.keyboard.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * DominionKeyboardIME — The core Input Method Service.
 *
 * Performance-optimized for GBoard-level responsiveness:
 *  - Deferred background initialization of heavy dependencies
 *  - Batched keystroke logging (writes every 2 seconds, not per-character)
 *  - Debounced suggestion updates (50ms after last keystroke)
 *  - Cached system services (Vibrator)
 *  - Haptic feedback via View.performHapticFeedback (hardware-optimized)
 */
class DominionKeyboardIME : InputMethodService() {

    companion object {
        private const val TAG = "DominionKeyboardIME"
        private const val AUDIO_FILE_NAME = "dominion_recording.m4a"
        private const val SUGGESTION_DEBOUNCE_MS = 50L
        private const val LOG_FLUSH_INTERVAL_MS = 2000L
    }

    // ─── Views ───────────────────────────────────────────────────────────────
    private var keyboardView: View? = null
    private var numbersView: View? = null
    private var currentView: KeyboardMode = KeyboardMode.LETTERS

    // ─── State ───────────────────────────────────────────────────────────────
    private var isShifted = false
    private var isCapsLock = false
    private var isRecording = false
    private var currentWordBuffer = StringBuilder()
    private var sessionId: Long = 0L
    private var lastShiftTapTime = 0L

    // ─── Coroutines ──────────────────────────────────────────────────────────
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var suggestionJob: Job? = null

    // ─── Dependencies (lazy/deferred init for fast startup) ──────────────────
    private var db: KeyboardDatabase? = null
    private var openAIClient: OpenAIClient? = null
    private var predictiveEngine: PredictiveTextEngine? = null
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

    // ─── Cached Vibrator ─────────────────────────────────────────────────────
    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null

    // ─── Batched Logging ─────────────────────────────────────────────────────
    private val logQueue = ConcurrentLinkedQueue<KeystrokeLog>()
    private var logFlushJob: Job? = null
    private var isInitialized = false

    enum class KeyboardMode { LETTERS, NUMBERS }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        // Only initialize lightweight prefs synchronously
        prefsManager = PrefsManager(applicationContext)

        // Cache vibrator for zero-alloc haptics
        initVibrator()

        // Defer ALL heavy initialization to background
        serviceScope.launch(Dispatchers.IO) {
            db = KeyboardDatabase.getInstance(applicationContext)
            openAIClient = OpenAIClient(applicationContext)
            predictiveEngine = PredictiveTextEngine(applicationContext)
            isInitialized = true
            Log.d(TAG, "DominionKeyboardIME background init complete")
        }

        // Start the batched log flusher
        startLogFlusher()

        Log.d(TAG, "DominionKeyboardIME created (fast path)")
    }

    override fun onCreateInputView(): View {
        val inflater = LayoutInflater.from(this)

        keyboardView = inflater.inflate(R.layout.keyboard_view, null)
        numbersView = inflater.inflate(R.layout.keyboard_numbers, null)

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
        // Start session log on background — non-blocking
        serviceScope.launch(Dispatchers.IO) {
            try {
                val database = db ?: return@launch
                sessionId = database.sessionLogDao().insert(
                    SessionLog(
                        startTime = Instant.now().toString(),
                        appPackage = currentInputEditorInfo?.packageName ?: "unknown"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Session log insert failed", e)
            }
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (sessionId > 0) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    db?.sessionLogDao()?.closeSession(sessionId, Instant.now().toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Session close failed", e)
                }
            }
        }
        // Flush any remaining logs
        flushLogQueue()
        stopRecordingIfActive()
    }

    override fun onDestroy() {
        super.onDestroy()
        flushLogQueue()
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

        attachKeyListeners(view)

        suggestion1?.setOnClickListener { onSuggestionTapped(suggestion1?.text?.toString()) }
        suggestion2?.setOnClickListener { onSuggestionTapped(suggestion2?.text?.toString()) }
        suggestion3?.setOnClickListener { onSuggestionTapped(suggestion3?.text?.toString()) }
    }

    private fun setupNumberKeyboard(view: View) {
        attachKeyListeners(view)
    }

    private fun attachKeyListeners(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachKeyListeners(root.getChildAt(i))
            }
        } else if (root is Button) {
            root.setOnClickListener { v -> onKeyPressed(v as Button) }
            if (root.tag == "DELETE") {
                root.setOnLongClickListener {
                    clearAllText()
                    true
                }
            }
            if (root.tag == "SHIFT") {
                root.setOnLongClickListener {
                    toggleCapsLock()
                    true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY PRESS HANDLING (HOT PATH — ZERO ALLOCATIONS)
    // ═════════════════════════════════════════════════════════════════════════

    private fun onKeyPressed(button: Button) {
        val tag = button.tag?.toString() ?: return

        // Haptic feedback via the view system (hardware-optimized, no allocation)
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        when (tag) {
            "SHIFT" -> handleShift()
            "DELETE" -> handleDelete()
            "SPACE" -> handleSpace()
            "ENTER" -> handleEnter()
            "NUMBERS" -> switchToNumbers()
            "LETTERS" -> switchToLetters()
            "SYMBOLS2" -> { /* Future expansion */ }
            "MIC" -> handleMicToggle()
            "TRANSLATE" -> handleTranslate()
            else -> handleCharacter(tag)
        }
    }

    private fun handleCharacter(char: String) {
        val ic = currentInputConnection ?: return
        val output = if (isShifted || isCapsLock) char.uppercase() else char.lowercase()
        ic.commitText(output, 1)

        // Queue log (batched, non-blocking)
        queueKeystrokeLog(output, "character")

        // Update word buffer for predictions
        currentWordBuffer.append(output)
        updateSuggestionsDebounced(currentWordBuffer.toString())

        // Auto-unshift after one capital letter (unless caps lock)
        if (isShifted && !isCapsLock) {
            isShifted = false
            updateShiftState()
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
        queueKeystrokeLog(" ", "space")

        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            queueKeystrokeLog(word, "word_complete")
            predictiveEngine?.learnWord(word)
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (imeAction != null && imeAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(imeAction)
        } else {
            ic.commitText("\n", 1)
        }
        queueKeystrokeLog("\n", "enter")

        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            queueKeystrokeLog(word, "word_complete")
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        queueKeystrokeLog("", "delete")

        if (currentWordBuffer.isNotEmpty()) {
            currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
            updateSuggestionsDebounced(currentWordBuffer.toString())
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
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.endBatchEdit()
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
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
    // DEBOUNCED SUGGESTIONS (prevents coroutine spam on fast typing)
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateSuggestionsDebounced(prefix: String) {
        suggestionJob?.cancel()
        suggestionJob = serviceScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            val engine = predictiveEngine ?: return@launch
            val suggestions = withContext(Dispatchers.Default) {
                engine.getSuggestions(prefix, 3)
            }
            suggestion1?.text = suggestions.getOrNull(0) ?: ""
            suggestion2?.text = suggestions.getOrNull(1) ?: ""
            suggestion3?.text = suggestions.getOrNull(2) ?: ""
        }
    }

    private fun onSuggestionTapped(word: String?) {
        if (word.isNullOrBlank()) return
        val ic = currentInputConnection ?: return

        if (currentWordBuffer.isNotEmpty()) {
            ic.deleteSurroundingText(currentWordBuffer.length, 0)
        }

        ic.commitText("$word ", 1)
        queueKeystrokeLog(word, "word_complete")
        predictiveEngine?.learnWord(word)
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BATCHED KEYSTROKE LOGGING (writes every 2s instead of per-character)
    // ═════════════════════════════════════════════════════════════════════════

    private fun queueKeystrokeLog(value: String, type: String) {
        if (!prefsManager.isKeystrokeLoggingEnabled()) return
        logQueue.add(
            KeystrokeLog(
                sessionId = sessionId,
                keystrokeValue = value,
                keystrokeType = type,
                timestamp = Instant.now().toString(),
                appPackage = currentInputEditorInfo?.packageName ?: "unknown"
            )
        )
    }

    private fun startLogFlusher() {
        logFlushJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(LOG_FLUSH_INTERVAL_MS)
                flushLogQueue()
            }
        }
    }

    private fun flushLogQueue() {
        if (logQueue.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val database = db ?: return@launch
                val batch = mutableListOf<KeystrokeLog>()
                while (logQueue.isNotEmpty()) {
                    logQueue.poll()?.let { batch.add(it) }
                }
                if (batch.isNotEmpty()) {
                    database.keystrokeLogDao().insertAll(batch)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch log flush failed", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VOICE DICTATION (WHISPER — kept for accuracy)
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
            showStatus("🎤 Listening…")
            queueKeystrokeLog("", "mic_start")

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
        showStatus("⏳ Processing…")

        val file = audioFile ?: return
        if (!file.exists() || file.length() == 0L) {
            hideStatus()
            showToast("No audio captured")
            return
        }

        serviceScope.launch {
            try {
                val client = openAIClient ?: run {
                    hideStatus()
                    showToast("AI not ready yet")
                    return@launch
                }
                val transcript = withContext(Dispatchers.IO) {
                    client.transcribeAudio(file)
                }
                if (transcript != null) {
                    currentInputConnection?.commitText(transcript, 1)
                    queueKeystrokeLog(transcript, "whisper_dictation")
                    currentWordBuffer.clear()
                    updateSuggestionsDebounced("")
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
        val textBeforeCursor = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (textBeforeCursor.isBlank()) {
            showToast("Nothing to translate")
            return
        }

        showStatus("🌐 Translating…")

        serviceScope.launch {
            try {
                val client = openAIClient ?: run {
                    hideStatus()
                    showToast("AI not ready yet")
                    return@launch
                }
                val translated = withContext(Dispatchers.IO) {
                    client.translateToSpanish(textBeforeCursor)
                }
                if (translated != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(textBeforeCursor.length, 0)
                    ic.commitText(translated, 1)
                    ic.endBatchEdit()
                    queueKeystrokeLog(translated, "translation_es")
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
    // HAPTIC FEEDBACK (cached, zero-allocation)
    // ═════════════════════════════════════════════════════════════════════════

    private fun initVibrator() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrationEffect = VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        } catch (e: Exception) {
            // Non-critical
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun showStatus(message: String) {
        statusBar?.text = message
        statusBar?.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusBar?.visibility = View.GONE
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
