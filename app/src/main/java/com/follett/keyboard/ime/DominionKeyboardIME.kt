package com.follett.keyboard.ime

import android.graphics.PointF
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * DominionKeyboardIME — Production-grade Input Method Service.
 *
 * Architecture: Custom Canvas rendering + deferred AI services.
 * All settings are enforced. Password fields disable AI/logging.
 * Key repeat on DELETE. Auto-capitalize. Double-space-to-period.
 */
class DominionKeyboardIME : InputMethodService(), KeyboardCanvasView.KeyListener {

    companion object {
        private const val TAG = "DominionIME"
        private const val AUDIO_FILE_NAME = "dominion_recording.m4a"
        private const val SUGGESTION_DEBOUNCE_MS = 60L
        private const val AI_PREDICTION_DEBOUNCE_MS = 800L
        private const val LOG_FLUSH_INTERVAL_MS = 3000L
        private const val SENTENCE_BUFFER_MAX = 200
    }
    // ─── Views ───────────────────────────────────────────────────────────────────
    private var rootView: LinearLayout? = null
    private var keyboardCanvas: KeyboardCanvasView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var currentMode: KeyboardMode = KeyboardMode.LETTERS
    private var currentPanel: PanelMode = PanelMode.KEYBOARD

    enum class PanelMode { KEYBOARD, EMOJI, CLIPBOARD }

    private var suggestion1: TextView? = null
    private var suggestion2: TextView? = null
    private var suggestion3: TextView? = null
    private var statusBar: TextView? = null

    // ─── State ───────────────────────────────────────────────────────────────
    private var isShifted = false
    private var isCapsLock = false
    private var isRecording = false
    private var isPasswordField = false
    private var currentWordBuffer = StringBuilder()
    private var sentenceBuffer = StringBuilder()
    private var sessionId: Long = 0L
    private var lastShiftTapTime = 0L
    private var lastKeyTime = 0L

    // ─── Coroutines ──────────────────────────────────────────────────────────
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var suggestionJob: Job? = null
    private var aiPredictionJob: Job? = null
    private var statusHideJob: Job? = null

    // ─── Composing & Undo ─────────────────────────────────────────────────────
    private var isComposing = false
    private var lastCommittedSuggestion: String? = null  // For undo-autocorrect
    private var originalWordBeforeSuggestion: String? = null  // What user actually typed

    // ─── Dependencies ────────────────────────────────────────────────────────
    private var db: KeyboardDatabase? = null
    private var openAIClient: OpenAIClient? = null
    private var predictiveEngine: PredictiveTextEngine? = null
    private var inputIntelligence: InputIntelligenceEngine? = null
    private var gestureDecoder: GestureDecoder? = null
    private var currentTheme: KeyboardTheme = KeyboardTheme.DARK
    private lateinit var prefsManager: PrefsManager

    // ─── Audio ───────────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    // ─── Batched Logging ─────────────────────────────────────────────────────
    private val logQueue = ConcurrentLinkedQueue<KeystrokeLog>()

    enum class KeyboardMode { LETTERS, NUMBERS }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager(applicationContext)

        serviceScope.launch(Dispatchers.IO) {
            db = KeyboardDatabase.getInstance(applicationContext)
            openAIClient = OpenAIClient(applicationContext)
            predictiveEngine = PredictiveTextEngine(applicationContext)
            inputIntelligence = InputIntelligenceEngine(db?.inputCaptureDao())
            gestureDecoder = GestureDecoder()
        }

        // Batched log flusher
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(LOG_FLUSH_INTERVAL_MS)
                flushLogQueue()
            }
        }
    }

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFF0D0D1A.toInt())
        }

        rootView!!.addView(createSuggestionBar(d))

        statusBar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0x331A1A3A.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            setTextColor(0xFFB388FF.toInt())
            textSize = 12f
            visibility = View.GONE
        }
        rootView!!.addView(statusBar)

        keyboardCanvas = KeyboardCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            keyListener = this@DominionKeyboardIME
            gestureListener = object : KeyboardCanvasView.GestureListener {
                override fun onGestureCompleted(
                    path: List<android.graphics.PointF>,
                    keyRects: List<RectF>,
                    keys: List<KeyboardCanvasView.Key>
                ) {
                    handleGestureSwipe(path, keyRects, keys)
                }
            }
            soundEnabled = prefsManager.isSoundEnabled()
            isHapticFeedbackEnabled = prefsManager.isHapticEnabled()
            applyTheme(currentTheme)
            setKeyboard(KeyboardCanvasView.createQwertyLayout())
        }
        rootView!!.addView(keyboardCanvas)

        return rootView!!
    }

    private fun createSuggestionBar(d: Float): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (40 * d).toInt()
            )
            setBackgroundColor(0xFF121228.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        }

        val chipParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        suggestion1 = makeSuggestionChip(d).also { it.layoutParams = chipParams }
        suggestion2 = makeSuggestionChip(d).also { it.layoutParams = chipParams }
        suggestion3 = makeSuggestionChip(d).also { it.layoutParams = chipParams }

        suggestion1!!.setOnClickListener { onSuggestionTapped(suggestion1?.text?.toString()) }
        suggestion2!!.setOnClickListener { onSuggestionTapped(suggestion2?.text?.toString()) }
        suggestion3!!.setOnClickListener { onSuggestionTapped(suggestion3?.text?.toString()) }

        bar.addView(suggestion1)
        bar.addView(makeDivider(d))
        bar.addView(suggestion2)
        bar.addView(makeDivider(d))
        bar.addView(suggestion3)
        return bar
    }

    private fun makeSuggestionChip(d: Float) = TextView(this).apply {
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFE8E8FF.toInt())
        textSize = 14f
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        isClickable = true
    }

    private fun makeDivider(d: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (20 * d).toInt()).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        setBackgroundColor(0xFF2A2A4A.toInt())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INPUT SESSION LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputIntelligence?.startSession(sessionId)

        // Detect password/sensitive fields
        val inputType = info?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        isPasswordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0

        // Auto-capitalize at start of input
        if (prefsManager.isAutoCapitalizeEnabled() && !isPasswordField) {
            isShifted = true
            keyboardCanvas?.setShiftState(true, false)
        }

        // Session logging (skip for password fields)
        if (!isPasswordField) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    sessionId = db?.sessionLogDao()?.insert(
                        SessionLog(
                            startTime = Instant.now().toString(),
                            appPackage = info?.packageName ?: "unknown"
                        )
                    ) ?: 0L
                } catch (_: Exception) {}
            }
        }

        currentWordBuffer.clear()
        sentenceBuffer.clear()
        if (!isPasswordField) updateSuggestionsDebounced("")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isComposing) {
            currentInputConnection?.finishComposingText()
            isComposing = false
        }
        if (sessionId > 0 && !isPasswordField) {
            serviceScope.launch(Dispatchers.IO) {
                try { db?.sessionLogDao()?.closeSession(sessionId, Instant.now().toString()) }
                catch (_: Exception) {}
            }
        }
        flushLogQueue()
        stopRecordingIfActive()
        aiPredictionJob?.cancel()
        openAIClient?.cancelActiveRequests()
    }

    override fun onDestroy() {
        super.onDestroy()
        flushLogQueue()
        openAIClient?.cancelActiveRequests()
        serviceJob.cancel()
        stopRecordingIfActive()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY LISTENER
    // ═════════════════════════════════════════════════════════════════════════

    override fun onKeyPressed(key: KeyboardCanvasView.Key) {
        lastKeyTime = System.currentTimeMillis()
        when (key.tag) {
            "SHIFT" -> handleShift()
            "DELETE" -> handleDelete()
            "SPACE" -> handleSpace()
            "ENTER" -> handleEnter()
            "NUMBERS" -> switchToNumbers()
            "LETTERS" -> switchToLetters()
            "EMOJI" -> switchToEmoji()
            "MIC" -> handleMicToggle()
            "TRANSLATE" -> handleTranslate()
            "," -> handlePunctuation(",")
            "." -> handlePunctuation(".")
            else -> handleCharacter(key.tag)
        }
    }

    override fun onKeyLongPressed(key: KeyboardCanvasView.Key): Boolean {
        return when (key.tag) {
            "SHIFT" -> { toggleCapsLock(); true }
            "ENTER" -> { performEnterAction(); true }
            "SPACE" -> { switchToClipboard(); true }
            else -> false
        }
    }

    override fun onKeyRepeated(key: KeyboardCanvasView.Key) {
        // Key repeat — only DELETE uses this
        if (key.tag == "DELETE") {
            handleDelete()
            if (prefsManager.isHapticEnabled()) {
                keyboardCanvas?.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                )
            }
        }
    }

    override fun onKeyReleased(key: KeyboardCanvasView.Key) {
        // No action needed
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY ACTIONS
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleCharacter(char: String) {
        val ic = currentInputConnection ?: return
        val output = if (isShifted || isCapsLock) char.uppercase() else char.lowercase()

        // Clear undo state — user moved on
        lastCommittedSuggestion = null
        originalWordBeforeSuggestion = null

        currentWordBuffer.append(output)
        ic.setComposingText(currentWordBuffer.toString(), 1)
        isComposing = true

        if (!isPasswordField) {
            queueLog(output, "character")
            updateSuggestionsDebounced(currentWordBuffer.toString())
            if (prefsManager.isSmartComposeEnabled()) triggerAIPrediction()
        }

        if (isShifted && !isCapsLock) {
            isShifted = false
            keyboardCanvas?.setShiftState(false, false)
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        val word = currentWordBuffer.toString().trim()

        // Double-space-to-period
        if (prefsManager.isAutoPunctuateEnabled() && word.isEmpty()) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before == " " || before.endsWith(" ")) {
                // Replace trailing space with period + space
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                // Auto-capitalize after period
                if (prefsManager.isAutoCapitalizeEnabled()) {
                    isShifted = true
                    keyboardCanvas?.setShiftState(true, false)
                }
                return
            }
        }

        // AUTOCORRECT (GBoard-style rules):
        // 1. NEVER autocorrect single-character words (a, I, etc.)
        // 2. NEVER autocorrect if the word IS in the dictionary (it's valid)
        // 3. Uses edit distance to find corrections for actual typos
        // 4. Only autocorrect words 3+ characters long
        var committedWord = word
        if (isComposing && word.length >= 3 && !isPasswordField) {
            val engine = predictiveEngine
            if (engine != null && !engine.isValidWord(word)) {
                // Use edit-distance matching for real typo correction
                val corrections = engine.getAutocorrectCandidates(word, 1)
                if (corrections.isNotEmpty()) {
                    val corrected = corrections[0]
                    ic.setComposingText(corrected, 1)
                    committedWord = corrected
                    originalWordBeforeSuggestion = word
                    lastCommittedSuggestion = corrected
                }
            }
            ic.finishComposingText()
            isComposing = false
        } else if (isComposing) {
            ic.finishComposingText()
            isComposing = false
        }
        ic.commitText(" ", 1)

        if (!isPasswordField) {
            queueLog(" ", "space")
            if (committedWord.isNotEmpty()) {
                queueLog(committedWord, "word_complete")
                predictiveEngine?.learnWord(committedWord)
                appendToSentenceBuffer(committedWord + " ")
            }
            updateSuggestionsDebounced("")
        }
        currentWordBuffer.clear()
    }

    private fun handlePunctuation(char: String) {
        val ic = currentInputConnection ?: return
        val word = currentWordBuffer.toString().trim()

        // Autocorrect the composing word before punctuation (same as space)
        var committedWord = word
        if (isComposing && word.length >= 3 && !isPasswordField) {
            val engine = predictiveEngine
            if (engine != null && !engine.isValidWord(word)) {
                val corrections = engine.getAutocorrectCandidates(word, 1)
                if (corrections.isNotEmpty()) {
                    ic.setComposingText(corrections[0], 1)
                    committedWord = corrections[0]
                }
            }
            ic.finishComposingText()
            isComposing = false
        } else if (isComposing) {
            ic.finishComposingText()
            isComposing = false
        }

        // ALWAYS commit the punctuation character
        ic.commitText(char, 1)

        if (!isPasswordField) {
            queueLog(char, "character")
            if (committedWord.isNotEmpty()) {
                queueLog(committedWord, "word_complete")
                predictiveEngine?.learnWord(committedWord)
                appendToSentenceBuffer(committedWord + char)
            }
        }
        currentWordBuffer.clear()
        lastCommittedSuggestion = null
        originalWordBeforeSuggestion = null

        // Auto-capitalize after sentence-ending punctuation
        if (char in listOf(".", "!", "?") && prefsManager.isAutoCapitalizeEnabled()) {
            isShifted = true
            keyboardCanvas?.setShiftState(true, false)
        }

        if (!isPasswordField) updateSuggestionsDebounced("")
    }

    private fun handleEnter() {
        // Tap Enter = ALWAYS newline (user preference)
        val ic = currentInputConnection ?: return
        if (isComposing) { ic.finishComposingText(); isComposing = false }
        ic.commitText("\n", 1)

        if (!isPasswordField) queueLog("\n", "enter")
        currentWordBuffer.clear()
        sentenceBuffer.clear()

        // Auto-capitalize new line
        if (prefsManager.isAutoCapitalizeEnabled()) {
            isShifted = true
            keyboardCanvas?.setShiftState(true, false)
        }
        if (!isPasswordField) updateSuggestionsDebounced("")
    }

    /**
     * Long-press Enter = execute the app's IME action (Send, Search, Go, etc.)
     * Before sending, runs GPT autocorrect on the full sentence for context-aware correction.
     */
    private fun performEnterAction() {
        val ic = currentInputConnection ?: return
        if (isComposing) { ic.finishComposingText(); isComposing = false }

        // GPT context-aware autocorrect before sending
        val textBeforeSend = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (!isPasswordField && textBeforeSend.length >= 3 && prefsManager.isSmartComposeEnabled()) {
            serviceScope.launch {
                val corrected = withContext(Dispatchers.IO) {
                    openAIClient?.correctWithContext(textBeforeSend)
                }
                if (corrected != null && corrected != textBeforeSend && corrected.isNotBlank()) {
                    // Replace the text with corrected version
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(textBeforeSend.length, 0)
                    ic.commitText(corrected, 1)
                    ic.endBatchEdit()
                }

                // Now send
                val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (imeAction != null && imeAction != EditorInfo.IME_ACTION_NONE) {
                    ic.performEditorAction(imeAction)
                } else {
                    ic.commitText("\n", 1)
                }

                // Capture for Input Intelligence
                val finalText = corrected ?: textBeforeSend
                withContext(Dispatchers.IO) {
                    inputIntelligence?.captureMessage(
                        fullText = finalText.trim(),
                        editorInfo = currentInputEditorInfo
                    )
                }
            }
        } else {
            // No GPT correction — just send immediately
            if (!isPasswordField && sentenceBuffer.isNotEmpty()) {
                serviceScope.launch(Dispatchers.IO) {
                    inputIntelligence?.captureMessage(
                        fullText = sentenceBuffer.toString().trim(),
                        editorInfo = currentInputEditorInfo
                    )
                }
            }

            val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            if (imeAction != null && imeAction != EditorInfo.IME_ACTION_NONE) {
                ic.performEditorAction(imeAction)
            } else {
                ic.commitText("\n", 1)
            }
        }

        if (!isPasswordField) queueLog("\n", "enter_action")
        currentWordBuffer.clear()
        sentenceBuffer.clear()
        if (!isPasswordField) updateSuggestionsDebounced("")
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return

        // Undo last autocorrect: if user hits backspace right after accepting a suggestion,
        // revert to what they originally typed
        if (lastCommittedSuggestion != null && originalWordBeforeSuggestion != null) {
            val suggestion = lastCommittedSuggestion!!
            val original = originalWordBeforeSuggestion!!
            // Delete the suggestion + trailing space
            ic.deleteSurroundingText(suggestion.length + 1, 0)
            // Re-insert original as composing text
            currentWordBuffer.clear()
            currentWordBuffer.append(original)
            ic.setComposingText(original, 1)
            isComposing = true
            lastCommittedSuggestion = null
            originalWordBeforeSuggestion = null
            if (!isPasswordField) updateSuggestionsDebounced(original)
            return
        }

        // Normal delete behavior
        lastCommittedSuggestion = null
        originalWordBeforeSuggestion = null

        if (isComposing && currentWordBuffer.isNotEmpty()) {
            currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
            if (currentWordBuffer.isEmpty()) {
                ic.finishComposingText()
                isComposing = false
            } else {
                ic.setComposingText(currentWordBuffer.toString(), 1)
            }
        } else {
            isComposing = false
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        if (!isPasswordField) {
            queueLog("", "delete")
            updateSuggestionsDebounced(currentWordBuffer.toString())
        }
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 350) {
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

    private fun switchToNumbers() {
        currentMode = KeyboardMode.NUMBERS
        keyboardCanvas?.setKeyboard(KeyboardCanvasView.createNumbersLayout())
    }

    private fun switchToLetters() {
        currentMode = KeyboardMode.LETTERS
        keyboardCanvas?.setKeyboard(KeyboardCanvasView.createQwertyLayout())
        keyboardCanvas?.setShiftState(isShifted, isCapsLock)
    }

    private fun switchToEmoji() {
        if (emojiPanel == null) {
            emojiPanel = EmojiPanelView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                emojiListener = object : EmojiPanelView.EmojiListener {
                    override fun onEmojiSelected(emoji: String) {
                        currentInputConnection?.commitText(emoji, 1)
                    }
                    override fun onBackToKeyboard() {
                        switchBackToKeyboard()
                    }
                }
            }
        }
        currentPanel = PanelMode.EMOJI
        setInputView(emojiPanel!!)
    }

    private fun switchToClipboard() {
        if (clipboardPanel == null) {
            clipboardPanel = ClipboardPanel(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                clipboardListener = object : ClipboardPanel.ClipboardListener {
                    override fun onClipboardItemSelected(text: String) {
                        currentInputConnection?.commitText(text, 1)
                        switchBackToKeyboard()
                    }
                    override fun onBackToKeyboard() {
                        switchBackToKeyboard()
                    }
                }
            }
        }
        clipboardPanel?.refreshClipboard()
        currentPanel = PanelMode.CLIPBOARD
        setInputView(clipboardPanel!!)
    }

    private fun switchBackToKeyboard() {
        currentPanel = PanelMode.KEYBOARD
        setInputView(rootView!!)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SUGGESTIONS
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateSuggestionsDebounced(prefix: String) {
        if (isPasswordField) return
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

    private fun triggerAIPrediction() {
        if (isPasswordField) return
        if (!prefsManager.isSmartComposeEnabled()) return
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) return

        aiPredictionJob?.cancel()
        aiPredictionJob = serviceScope.launch {
            delay(AI_PREDICTION_DEBOUNCE_MS)
            val client = openAIClient ?: return@launch
            val context = sentenceBuffer.toString() + currentWordBuffer.toString()
            if (context.length < 5) return@launch

            val predictions = withContext(Dispatchers.IO) {
                try { client.getSmartCompletions(context) }
                catch (_: Exception) { emptyList() }
            }
            if (predictions.isNotEmpty()) {
                suggestion1?.text = predictions.getOrNull(0) ?: ""
                suggestion2?.text = predictions.getOrNull(1) ?: ""
                suggestion3?.text = predictions.getOrNull(2) ?: ""
            }
        }
    }

    private fun onSuggestionTapped(word: String?) {
        if (word.isNullOrBlank()) return
        val ic = currentInputConnection ?: return

        // Save for undo
        originalWordBeforeSuggestion = currentWordBuffer.toString()

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

        lastCommittedSuggestion = word

        if (!isPasswordField) {
            queueLog(word, "word_complete")
            predictiveEngine?.learnWord(word)
            appendToSentenceBuffer("$word ")
        }
        currentWordBuffer.clear()
        updateSuggestionsDebounced("")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SENTENCE BUFFER (capped for safety)
    // ═════════════════════════════════════════════════════════════════════════

    private fun appendToSentenceBuffer(text: String) {
        sentenceBuffer.append(text)
        if (sentenceBuffer.length > SENTENCE_BUFFER_MAX) {
            val trimmed = sentenceBuffer.substring(sentenceBuffer.length - SENTENCE_BUFFER_MAX)
            sentenceBuffer.clear()
            sentenceBuffer.append(trimmed)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BATCHED LOGGING
    // ═════════════════════════════════════════════════════════════════════════

    private fun queueLog(value: String, type: String) {
        if (isPasswordField) return
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
            } catch (_: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VOICE DICTATION (WHISPER)
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleMicToggle() {
        if (isRecording) stopRecordingAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showStatus("⚠️ Open Dominion app → enter API key")
            return
        }
        if (openAIClient == null) {
            showStatus("⏳ AI loading...")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showStatus("⚠️ Mic permission needed — open app to grant")
            return
        }

        audioFile = File(cacheDir, AUDIO_FILE_NAME)
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
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
            showStatus("🎤 Recording… tap mic to stop", persistent = true)
        } catch (e: Exception) {
            showStatus("❌ ${e.message?.take(50)}")
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
        }
    }

    private fun stopRecordingAndTranscribe() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        } finally {
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
        }

        isRecording = false
        showStatus("⏳ Transcribing...", persistent = true)

        val file = audioFile
        if (file == null || !file.exists() || file.length() == 0L) {
            showStatus("❌ No audio captured")
            return
        }

        serviceScope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    openAIClient?.transcribeAudio(file)
                }
                if (!transcript.isNullOrBlank()) {
                    currentInputConnection?.commitText(transcript, 1)
                    queueLog(transcript, "whisper_dictation")
                    // Capture voice dictation for Input Intelligence
                    withContext(Dispatchers.IO) {
                        inputIntelligence?.captureMessage(
                            fullText = transcript,
                            editorInfo = currentInputEditorInfo,
                            wasVoice = true
                        )
                    }
                    currentWordBuffer.clear()
                    showStatus("✅ Done")
                } else {
                    showStatus("❌ Transcription failed")
                }
            } catch (e: Exception) {
                showStatus("❌ Network error")
            }
        }
    }

    private fun stopRecordingIfActive() {
        if (isRecording) {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRANSLATION (Honduran Spanish)
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleTranslate() {
        val apiKey = prefsManager.getApiKey()
        if (apiKey.isNullOrBlank()) { showStatus("⚠️ Set API key first"); return }
        if (openAIClient == null) { showStatus("⏳ AI loading..."); return }

        val ic = currentInputConnection ?: return
        val text = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        if (text.isBlank()) { showStatus("Nothing to translate"); return }

        showStatus("🌐 Translating...", persistent = true)
        serviceScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) {
                    openAIClient?.translateToSpanish(text)
                }
                if (!translated.isNullOrBlank()) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(text.length, 0)
                    ic.commitText(translated, 1)
                    ic.endBatchEdit()
                    queueLog(translated, "translation_es")
                    // Capture translation for Input Intelligence
                    withContext(Dispatchers.IO) {
                        inputIntelligence?.captureMessage(
                            fullText = translated,
                            editorInfo = currentInputEditorInfo,
                            wasTranslated = true
                        )
                    }
                    showStatus("✅ Translated")
                } else {
                    showStatus("❌ Translation failed")
                }
            } catch (_: Exception) {
                showStatus("❌ Network error")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun showStatus(msg: String, persistent: Boolean = false) {
        statusBar?.text = msg
        statusBar?.visibility = View.VISIBLE
        statusHideJob?.cancel()
        if (!persistent) {
            statusHideJob = serviceScope.launch {
                delay(3000)
                statusBar?.visibility = View.GONE
            }
        }
    }

    private fun hideStatus() {
        statusHideJob?.cancel()
        statusBar?.visibility = View.GONE
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GESTURE/SWIPE TYPING
    // ═════════════════════════════════════════════════════════════════════════

    private fun handleGestureSwipe(
        path: List<PointF>,
        keyRects: List<RectF>,
        keys: List<KeyboardCanvasView.Key>
    ) {
        // Check if gesture started on the spacebar — if so, it's cursor control
        if (path.isNotEmpty()) {
            val startPoint = path.first()
            val spaceKeyIndex = keys.indexOfFirst { it.tag == "SPACE" }
            if (spaceKeyIndex >= 0 && spaceKeyIndex < keyRects.size) {
                val spaceRect = keyRects[spaceKeyIndex]
                if (spaceRect.contains(startPoint.x, startPoint.y)) {
                    handleCursorSwipe(path)
                    return
                }
            }
        }

        // Otherwise it's swipe typing
        val decoder = gestureDecoder ?: return
        val engine = predictiveEngine ?: return
        val density = resources.displayMetrics.density

        if (!decoder.isSwipeGesture(path, density)) return

        val candidates = decoder.decode(path, keyRects, keys) { letterSequence ->
            engine.getSuggestions(letterSequence, 5)
        }

        if (candidates.isNotEmpty()) {
            val ic = currentInputConnection ?: return
            val word = candidates[0]
            if (isComposing) { ic.finishComposingText(); isComposing = false }
            ic.commitText("$word ", 1)

            if (!isPasswordField) {
                queueLog(word, "gesture_word")
                predictiveEngine?.learnWord(word)
                appendToSentenceBuffer("$word ")
            }
            currentWordBuffer.clear()

            suggestion1?.text = candidates.getOrNull(1) ?: ""
            suggestion2?.text = candidates.getOrNull(2) ?: ""
            suggestion3?.text = candidates.getOrNull(3) ?: ""
        }
    }

    /**
     * Cursor control: swipe left/right on spacebar to move the text cursor.
     * GBoard's most-loved hidden feature.
     */
    private fun handleCursorSwipe(path: List<PointF>) {
        if (path.size < 2) return
        val ic = currentInputConnection ?: return

        // Finish any composing text first
        if (isComposing) { ic.finishComposingText(); isComposing = false }

        val startX = path.first().x
        val endX = path.last().x
        val deltaX = endX - startX
        val density = resources.displayMetrics.density

        // Calculate how many characters to move (1 char per ~20dp of swipe)
        val charsMoved = (deltaX / (20f * density)).toInt()

        if (charsMoved > 0) {
            // Move cursor right
            for (i in 0 until charsMoved) {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            }
        } else if (charsMoved < 0) {
            // Move cursor left
            for (i in 0 until -charsMoved) {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            }
        }

        currentWordBuffer.clear()
    }
}
