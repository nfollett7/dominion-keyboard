package com.follett.keyboard.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.follett.keyboard.R
import com.follett.keyboard.api.AgentIntent
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
 * DominionKeyboardIME — High-performance Input Method Service.
 *
 * Architecture: Custom Canvas-based rendering (zero view hierarchy).
 * This is the same approach used by GBoard and all production keyboards.
 * A single KeyboardCanvasView draws all keys on a hardware-accelerated
 * Canvas and handles raw touch events with zero propagation delay.
 */
class DominionKeyboardIME : InputMethodService(), KeyboardCanvasView.KeyListener {

    companion object {
        private const val TAG = "DominionKeyboardIME"
        private const val AUDIO_FILE_NAME = "dominion_recording.m4a"
        private const val SUGGESTION_DEBOUNCE_MS = 50L
        private const val AI_PREDICTION_DEBOUNCE_MS = 500L  // Longer debounce for AI calls
        private const val LOG_FLUSH_INTERVAL_MS = 2000L
    }

    // ─── Views ───────────────────────────────────────────────────────────────
    private var rootView: LinearLayout? = null
    private var keyboardCanvas: KeyboardCanvasView? = null
    private var currentMode: KeyboardMode = KeyboardMode.LETTERS

    // ─── Suggestion bar ──────────────────────────────────────────────────────
    private var suggestion1: TextView? = null
    private var suggestion2: TextView? = null
    private var suggestion3: TextView? = null
    private var statusBar: TextView? = null

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
    private var aiPredictionJob: Job? = null

    // ─── Composing Text State ────────────────────────────────────────────────
    private var isComposing = false
    private var sentenceBuffer = StringBuilder()  // Full sentence context for AI

    // ─── Dependencies (deferred init) ────────────────────────────────────────
    private var db: KeyboardDatabase? = null
    private var openAIClient: OpenAIClient? = null
    private var predictiveEngine: PredictiveTextEngine? = null
    private lateinit var prefsManager: PrefsManager

    // ─── Audio ───────────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var micButton: KeyboardCanvasView.Key? = null

    // ─── Batched Logging ─────────────────────────────────────────────────────
    private val logQueue = ConcurrentLinkedQueue<KeystrokeLog>()

    enum class KeyboardMode { LETTERS, NUMBERS }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager(applicationContext)

        // Defer heavy init to background
        serviceScope.launch(Dispatchers.IO) {
            db = KeyboardDatabase.getInstance(applicationContext)
            openAIClient = OpenAIClient(applicationContext)
            predictiveEngine = PredictiveTextEngine(applicationContext)
            Log.d(TAG, "Background init complete")
        }

        // Start batched log flusher
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(LOG_FLUSH_INTERVAL_MS)
                flushLogQueue()
            }
        }
    }

    override fun onCreateInputView(): View {
        // Build the keyboard view programmatically — no XML inflation needed
        val density = resources.displayMetrics.density

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFF0D0D1A.toInt())
        }

        // Suggestion bar
        val suggestionBar = createSuggestionBar(density)
        rootView!!.addView(suggestionBar)

        // Status bar (hidden by default)
        statusBar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0x1A7B2FFF)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            setTextColor(0xFFB388FF.toInt())
            textSize = 11f
            visibility = View.GONE
        }
        rootView!!.addView(statusBar)

        // Canvas keyboard
        keyboardCanvas = KeyboardCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            keyListener = this@DominionKeyboardIME
            setKeyboard(KeyboardCanvasView.createQwertyLayout())
        }
        rootView!!.addView(keyboardCanvas)

        return rootView!!
    }

    private fun createSuggestionBar(density: Float): LinearLayout {
        val height = (40 * density).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
            setBackgroundColor(0xFF121228.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        val chipParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

        suggestion1 = createSuggestionChip(density)
        suggestion2 = createSuggestionChip(density)
        suggestion3 = createSuggestionChip(density)

        suggestion1!!.layoutParams = chipParams
        suggestion2!!.layoutParams = chipParams
        suggestion3!!.layoutParams = chipParams

        suggestion1!!.setOnClickListener { onSuggestionTapped(suggestion1?.text?.toString()) }
        suggestion2!!.setOnClickListener { onSuggestionTapped(suggestion2?.text?.toString()) }
        suggestion3!!.setOnClickListener { onSuggestionTapped(suggestion3?.text?.toString()) }

        bar.addView(suggestion1)
        bar.addView(createDivider(density))
        bar.addView(suggestion2)
        bar.addView(createDivider(density))
        bar.addView(suggestion3)

        return bar
    }

    private fun createSuggestionChip(density: Float): TextView {
        return TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFE8E8FF.toInt())
            textSize = 14f
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            isClickable = true
            isFocusable = true
        }
    }

    private fun createDivider(density: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (20 * density).toInt()).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setBackgroundColor(0xFF2A2A4A.toInt())
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        serviceScope.launch(Dispatchers.IO) {
            try {
                sessionId = db?.sessionLogDao()?.insert(
                    SessionLog(
                        startTime = Instant.now().toString(),
                        appPackage = currentInputEditorInfo?.packageName ?: "unknown"
                    )
                ) ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Session insert failed", e)
            }
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (sessionId > 0) {
            serviceScope.launch(Dispatchers.IO) {
                try { db?.sessionLogDao()?.closeSession(sessionId, Instant.now().toString()) }
                catch (e: Exception) { Log.e(TAG, "Session close failed", e) }
            }
        }
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
    // KEY LISTENER (from KeyboardCanvasView)
    // ═════════════════════════════════════════════════════════════════════════

    override fun onKeyPressed(key: KeyboardCanvasView.Key) {
        Log.d(TAG, "onKeyPressed: tag='${key.tag}'")
        when (key.tag) {
            "SHIFT" -> handleShift()
            "DELETE" -> handleDelete()
            "SPACE" -> handleSpace()
            "ENTER" -> handleEnter()
            "NUMBERS" -> switchToNumbers()
            "LETTERS" -> switchToLetters()
            "SYMBOLS2" -> { /* Future */ }
            "MIC" -> handleMicToggle()
            "TRANSLATE" -> handleTranslate()
            "," -> handlePunctuation(",")
            "." -> handlePunctuation(".")
            else -> handleCharacter(key.tag)
        }
    }

    override fun onKeyLongPressed(key: KeyboardCanvasView.Key): Boolean {
        return when (key.tag) {
            "DELETE" -> { clearAllText(); true }
            "SHIFT" -> { toggleCapsLock(); true }
            else -> false
        }
    }

    override fun onKeyReleased(key: KeyboardCanvasView.Key) {
        // No-op for now; could be used for key-repeat on DELETE
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY ACTIONS
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleCharacter(char: String) {
        val ic = currentInputConnection ?: return
        val output = if (isShifted || isCapsLock) char.uppercase() else char.lowercase()

        // Use composing text — word stays underlined until committed
        currentWordBuffer.append(output)
        ic.setComposingText(currentWordBuffer.toString(), 1)
        isComposing = true

        queueLog(output, "character")

        // Local suggestions (fast, offline)
        updateSuggestionsDebounced(currentWordBuffer.toString())

        // AI predictions (slower, context-aware) — fires after typing pause
        triggerAIPrediction()

        if (isShifted && !isCapsLock) {
            isShifted = false
            keyboardCanvas?.setShiftState(isShifted, isCapsLock)
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return

        // Commit the composing word + space
        val word = currentWordBuffer.toString().trim()
        if (isComposing) {
            ic.finishComposingText()
            isComposing = false
        }
        ic.commitText(" ", 1)

        queueLog(" ", "space")
        if (word.isNotEmpty()) {
            queueLog(word, "word_complete")
            predictiveEngine?.learnWord(word)
            sentenceBuffer.append(word).append(" ")
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    private fun handlePunctuation(char: String) {
        val ic = currentInputConnection ?: return
        // Finish composing before punctuation
        if (isComposing) {
            ic.finishComposingText()
            isComposing = false
        }
        ic.commitText(char, 1)
        queueLog(char, "character")
        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) {
            queueLog(word, "word_complete")
            predictiveEngine?.learnWord(word)
            sentenceBuffer.append(word).append(char)
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (isComposing) { ic.finishComposingText(); isComposing = false }
        val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (imeAction != null && imeAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(imeAction)
        } else {
            ic.commitText("\n", 1)
        }
        queueLog("\n", "enter")
        val word = currentWordBuffer.toString().trim()
        if (word.isNotEmpty()) queueLog(word, "word_complete")
        currentWordBuffer.clear()
        sentenceBuffer.clear()  // New sentence on enter
        updateSuggestionsDebounced("")
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        if (isComposing && currentWordBuffer.isNotEmpty()) {
            // Delete within composing region
            currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
            if (currentWordBuffer.isEmpty()) {
                ic.finishComposingText()
                isComposing = false
            } else {
                ic.setComposingText(currentWordBuffer.toString(), 1)
            }
        } else {
            // Normal delete
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        queueLog("", "delete")
        updateSuggestionsDebounced(currentWordBuffer.toString())
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 400) {
            toggleCapsLock()
        } else {
            isShifted = !isShifted
            if (isCapsLock) { isCapsLock = false; isShifted = false }
            keyboardCanvas?.setShiftState(isShifted, isCapsLock)
        }
        lastShiftTapTime = now
    }

    private fun toggleCapsLock() {
        isCapsLock = !isCapsLock
        isShifted = isCapsLock
        keyboardCanvas?.setShiftState(isShifted, isCapsLock)
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
        currentMode = KeyboardMode.NUMBERS
        keyboardCanvas?.setKeyboard(KeyboardCanvasView.createNumbersLayout())
    }

    private fun switchToLetters() {
        currentMode = KeyboardMode.LETTERS
        keyboardCanvas?.setKeyboard(KeyboardCanvasView.createQwertyLayout())
        keyboardCanvas?.setShiftState(isShifted, isCapsLock)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DEBOUNCED SUGGESTIONS
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

        // If composing, replace the composing text with the suggestion
        if (isComposing) {
            ic.setComposingText(word, 1)
            ic.finishComposingText()
            isComposing = false
        } else if (currentWordBuffer.isNotEmpty()) {
            ic.deleteSurroundingText(currentWordBuffer.length, 0)
            ic.commitText(word, 1)
        } else {
            ic.commitText(word, 1)
        }
        ic.commitText(" ", 1)

        queueLog(word, "word_complete")
        predictiveEngine?.learnWord(word)
        sentenceBuffer.append(word).append(" ")
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AI-POWERED PREDICTIONS (fires after typing pause)
    // ═════════════════════════════════════════════════════════════════════════

    private fun triggerAIPrediction() {
        aiPredictionJob?.cancel()
        aiPredictionJob = serviceScope.launch {
            delay(AI_PREDICTION_DEBOUNCE_MS)
            val client = openAIClient ?: return@launch
            val context = sentenceBuffer.toString() + currentWordBuffer.toString()
            if (context.length < 3) return@launch  // Need some context

            val predictions = withContext(Dispatchers.IO) {
                client.getSmartCompletions(context)
            }
            if (predictions.isNotEmpty()) {
                // AI predictions override local suggestions
                suggestion1?.text = predictions.getOrNull(0) ?: ""
                suggestion2?.text = predictions.getOrNull(1) ?: ""
                suggestion3?.text = predictions.getOrNull(2) ?: ""
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BATCHED LOGGING
    // ═════════════════════════════════════════════════════════════════════════

    private fun queueLog(value: String, type: String) {
        if (!prefsManager.isKeystrokeLoggingEnabled()) return
        logQueue.add(KeystrokeLog(
            sessionId = sessionId,
            keystrokeValue = value,
            keystrokeType = type,
            timestamp = Instant.now().toString(),
            appPackage = currentInputEditorInfo?.packageName ?: "unknown"
        ))
    }

    private fun flushLogQueue() {
        if (logQueue.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val batch = mutableListOf<KeystrokeLog>()
                while (logQueue.isNotEmpty()) { logQueue.poll()?.let { batch.add(it) } }
                if (batch.isNotEmpty()) db?.keystrokeLogDao()?.insertAll(batch)
            } catch (e: Exception) { Log.e(TAG, "Log flush failed", e) }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VOICE DICTATION (WHISPER)
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleMicToggle() {
        Log.d(TAG, "MIC pressed, isRecording=$isRecording")
        if (isRecording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val apiKey = prefsManager.getApiKey()
        Log.d(TAG, "startRecording: apiKey=${if (apiKey.isNullOrBlank()) "EMPTY" else "set (${apiKey.length} chars)"}")
        if (apiKey.isNullOrBlank()) {
            showStatus("⚠\uFE0F Set API key in app settings")
            return
        }
        if (openAIClient == null) {
            showStatus("⏳ AI loading, try again...")
            return
        }

        // Check runtime permission
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showStatus("⚠\uFE0F Mic permission needed — open app to grant")
            return
        }

        showStatus("🎤 Starting mic...")
        audioFile = File(cacheDir, AUDIO_FILE_NAME)
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else { @Suppress("DEPRECATION") MediaRecorder() }

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
            showStatus("🎤 Listening… tap mic to stop")
        } catch (e: Exception) {
            Log.e(TAG, "Mic error", e)
            showStatus("❌ Mic error: ${e.message?.take(40)}")
            mediaRecorder?.release(); mediaRecorder = null
        }
    }

    private fun stopRecordingAndTranscribe() {
        try { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null }
        catch (e: Exception) { Log.e(TAG, "Stop error", e) }

        isRecording = false
        showStatus("⏳ Processing…")

        val file = audioFile ?: return
        if (!file.exists() || file.length() == 0L) { hideStatus(); showToast("No audio"); return }

        serviceScope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) { openAIClient?.transcribeAudio(file) }
                if (transcript != null) {
                    currentInputConnection?.commitText(transcript, 1)
                    queueLog(transcript, "whisper_dictation")
                    currentWordBuffer.clear()
                    updateSuggestionsDebounced("")
                }
                hideStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                showToast(getString(R.string.error_network)); hideStatus()
            }
        }
    }

    private fun stopRecordingIfActive() {
        if (isRecording) {
            try { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null }
            catch (e: Exception) { Log.e(TAG, "Stop error", e) }
            isRecording = false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRANSLATION
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleTranslate() {
        Log.d(TAG, "TRANSLATE pressed")
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showStatus("⚠️ Set API key in app settings")
            return
        }
        if (openAIClient == null) {
            showStatus("⏳ AI loading, try again...")
            return
        }

        val ic = currentInputConnection ?: return
        val text = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (text.isBlank()) { showToast("Nothing to translate"); return }

        showStatus("🌐 Translating…")
        serviceScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) { openAIClient?.translateToSpanish(text) }
                if (translated != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(text.length, 0)
                    ic.commitText(translated, 1)
                    ic.endBatchEdit()
                    queueLog(translated, "translation_es")
                }
                hideStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                showToast(getString(R.string.error_network)); hideStatus()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun showStatus(msg: String) {
        statusBar?.text = msg
        statusBar?.visibility = View.VISIBLE
        // Auto-hide after 3 seconds for non-persistent messages
        serviceScope.launch {
            delay(3000)
            if (statusBar?.text == msg) {
                statusBar?.visibility = View.GONE
            }
        }
    }

    private fun hideStatus() { statusBar?.visibility = View.GONE }
    private fun showToast(msg: String) { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
}
