package com.follett.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * KeyboardCanvasView — Production-grade custom keyboard renderer.
 *
 * Architecture: Single hardware-accelerated View drawing all keys on Canvas.
 * Same approach as GBoard, SwiftKey, and all production Android keyboards.
 *
 * Features:
 *  - Zero view hierarchy (single Canvas draw call)
 *  - Pre-allocated Paint objects (zero GC in onDraw)
 *  - Proper key repeat on DELETE (accelerating interval)
 *  - Visual press feedback (brighter key on touch)
 *  - Key pop-up preview (enlarged label above pressed key)
 *  - Long-press detection for SHIFT (caps lock)
 *  - Direct touch-to-key mapping (<16ms response)
 */
class KeyboardCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── Listener ────────────────────────────────────────────────────────────
    interface KeyListener {
        fun onKeyPressed(key: Key)
        fun onKeyLongPressed(key: Key): Boolean
        fun onKeyRepeated(key: Key)
        fun onKeyReleased(key: Key)
    }

    var keyListener: KeyListener? = null
    var soundEnabled: Boolean = false
    private var audioManager: AudioManager? = null

    private fun playKeySound() {
        if (!soundEnabled) return
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }

    // ─── Key Data Model ──────────────────────────────────────────────────────
    data class Key(
        val label: String,
        val tag: String,
        val row: Int,
        val col: Float,
        val colSpan: Float,
        val style: KeyStyle = KeyStyle.NORMAL
    )

    enum class KeyStyle { NORMAL, SPECIAL, ACTION, MIC }

    // ─── Layout State ────────────────────────────────────────────────────────
    private var keys: List<Key> = emptyList()
    private var keyRects: List<RectF> = emptyList()
    private var numRows = 0
    private var isShifted = false
    private var isCapsLock = false

    // ─── Touch State ─────────────────────────────────────────────────────────
    private var pressedKeyIndex = -1
    private val handler = Handler(Looper.getMainLooper())

    // ─── Gesture Path Tracking ─────────────────────────────────────────────
    private val gesturePath = mutableListOf<android.graphics.PointF>()
    private var isGesturing = false

    interface GestureListener {
        fun onGestureCompleted(path: List<android.graphics.PointF>, keyRects: List<RectF>, keys: List<Key>)
    }
    var gestureListener: GestureListener? = null

    // ─── Key Repeat (DELETE) ─────────────────────────────────────────────────
    private var isRepeating = false
    private var repeatCount = 0
    private val repeatInitialDelay = 400L   // ms before repeat starts
    private val repeatIntervalFast = 35L    // ms between repeats (fast)
    private val repeatIntervalSlow = 80L    // ms between repeats (initial)
    private val repeatAccelerateAfter = 5   // repeats before acceleration

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (pressedKeyIndex >= 0) {
                val key = keys[pressedKeyIndex]
                keyListener?.onKeyRepeated(key)
                repeatCount++
                val interval = if (repeatCount > repeatAccelerateAfter) repeatIntervalFast else repeatIntervalSlow
                handler.postDelayed(this, interval)
                isRepeating = true
            }
        }
    }

    // ─── Long Press (SHIFT) ──────────────────────────────────────────────────
    private var longPressHandled = false
    private val longPressRunnable = Runnable {
        if (pressedKeyIndex >= 0) {
            val key = keys[pressedKeyIndex]
            longPressHandled = keyListener?.onKeyLongPressed(key) ?: false
            if (longPressHandled) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    // ─── Dimensions ──────────────────────────────────────────────────────────
    private var keyHeight = 0f
    private var keyMargin = 0f
    private var cornerRadius = 0f
    private var totalCols = 10f
    private var density = 1f

    // ─── Pre-allocated Paints ────────────────────────────────────────────────
    private val paintKeyNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E38.toInt(); style = Paint.Style.FILL
    }
    private val paintKeySpecial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A4E.toInt(); style = Paint.Style.FILL
    }
    private val paintKeyAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D3055.toInt(); style = Paint.Style.FILL
    }
    private val paintKeyMic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D1B3D.toInt(); style = Paint.Style.FILL
    }
    private val paintKeyPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A7A.toInt(); style = Paint.Style.FILL
    }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A5C.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val paintBorderAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8FF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val paintTextAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00D4FF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val paintTextSpecial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0D0.toInt(); textAlign = Paint.Align.CENTER
    }
    private val paintBg = Paint().apply {
        color = 0xFF0D0D1A.toInt(); style = Paint.Style.FILL
    }
    // Pop-up preview
    private val paintPopup = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A6A.toInt(); style = Paint.Style.FILL
    }
    private val paintPopupText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isHapticFeedbackEnabled = true
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    fun setKeyboard(layout: KeyboardLayout) {
        keys = layout.keys
        numRows = layout.numRows
        totalCols = layout.totalCols
        recalculateKeyRects()
        invalidate()
    }

    fun setShiftState(shifted: Boolean, capsLock: Boolean) {
        isShifted = shifted
        isCapsLock = capsLock
        invalidate()
    }

    /**
     * Apply a theme to all paint objects. Call before first draw.
     */
    fun applyTheme(theme: KeyboardTheme) {
        paintBg.color = theme.bgColor
        paintKeyNormal.color = theme.keyNormal
        paintKeySpecial.color = theme.keySpecial
        paintKeyAction.color = theme.keyAction
        paintKeyMic.color = theme.keyMic
        paintKeyPressed.color = theme.keyPressed
        paintBorder.color = theme.keyBorder
        paintBorderAction.color = theme.keyBorderAction
        paintText.color = theme.textNormal
        paintTextAction.color = theme.textAction
        paintTextSpecial.color = theme.textSpecial
        invalidate()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MEASUREMENT
    // ═════════════════════════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        density = resources.displayMetrics.density
        keyHeight = 52f * density
        keyMargin = 2.5f * density
        cornerRadius = 6f * density
        paintText.textSize = 18f * density
        paintTextAction.textSize = 16f * density
        paintTextSpecial.textSize = 13f * density
        paintPopupText.textSize = 24f * density

        val height = (keyHeight * numRows + keyMargin * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateKeyRects()
    }

    private fun recalculateKeyRects() {
        if (width == 0 || keys.isEmpty()) return
        val rects = mutableListOf<RectF>()
        val colWidth = width.toFloat() / totalCols

        for (key in keys) {
            val x = key.col * colWidth + keyMargin
            val y = key.row * keyHeight + keyMargin
            val w = key.colSpan * colWidth - keyMargin * 2
            val h = keyHeight - keyMargin * 2
            rects.add(RectF(x, y, x + w, y + h))
        }
        keyRects = rects
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        for (i in keys.indices) {
            val key = keys[i]
            val rect = keyRects.getOrNull(i) ?: continue
            val isPressed = (i == pressedKeyIndex)

            // Key background
            val fillPaint = when {
                isPressed -> paintKeyPressed
                key.style == KeyStyle.SPECIAL -> paintKeySpecial
                key.style == KeyStyle.ACTION -> paintKeyAction
                key.style == KeyStyle.MIC -> paintKeyMic
                else -> paintKeyNormal
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

            // Key border
            val borderPaint = if (key.style == KeyStyle.ACTION) paintBorderAction else paintBorder
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

            // Key label
            val textPaint = when (key.style) {
                KeyStyle.ACTION -> paintTextAction
                KeyStyle.SPECIAL -> paintTextSpecial
                else -> paintText
            }
            val label = getDisplayLabel(key)
            val textX = rect.centerX()
            val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, textX, textY, textPaint)
        }

        // Draw key pop-up preview for pressed letter keys
        if (pressedKeyIndex >= 0) {
            val key = keys[pressedKeyIndex]
            if (key.tag.length == 1 && key.tag[0].isLetter()) {
                drawKeyPopup(canvas, pressedKeyIndex)
            }
        }
    }

    private fun drawKeyPopup(canvas: Canvas, index: Int) {
        val rect = keyRects[index]
        val key = keys[index]
        val popupWidth = rect.width() * 1.4f
        val popupHeight = keyHeight * 1.3f
        val popupX = rect.centerX() - popupWidth / 2
        val popupY = rect.top - popupHeight - 4 * density

        val popupRect = RectF(popupX, popupY, popupX + popupWidth, popupY + popupHeight)
        canvas.drawRoundRect(popupRect, cornerRadius * 1.5f, cornerRadius * 1.5f, paintPopup)

        val label = getDisplayLabel(key)
        val textY = popupRect.centerY() - (paintPopupText.descent() + paintPopupText.ascent()) / 2
        canvas.drawText(label, popupRect.centerX(), textY, paintPopupText)
    }

    private fun getDisplayLabel(key: Key): String {
        val tag = key.tag
        if (tag.length == 1 && tag[0].isLetter()) {
            return if (isShifted || isCapsLock) tag.uppercase() else tag.lowercase()
        }
        return key.label
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOUCH HANDLING (GBoard-style: commit on UP, expanded targets)
    // ═════════════════════════════════════════════════════════════════════════

    // Keys that have long-press actions — character commit is DELAYED for these
    private val longPressKeys = setOf("SHIFT", "ENTER", "SPACE")
    // Keys that fire immediately on DOWN (no delay)
    private val immediateKeys = setOf("DELETE", "SPACE", "NUMBERS", "LETTERS", "MIC", "EMOJI", "TRANSLATE")
    private var pendingKeyCommit: Runnable? = null
    private val tapCommitDelay = 100L  // ms to wait before committing long-press keys

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePath.clear()
                isGesturing = false
                gesturePath.add(android.graphics.PointF(event.x, event.y))

                val index = findKeyNearest(event.x, event.y)
                if (index >= 0) {
                    pressedKeyIndex = index
                    longPressHandled = false
                    isRepeating = false
                    repeatCount = 0
                    invalidate()

                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playKeySound()

                    val key = keys[index]

                    // Immediate-fire keys (DELETE, SPACE, etc.) — no delay
                    if (key.tag in immediateKeys) {
                        keyListener?.onKeyPressed(key)
                    }

                    // DELETE: schedule key repeat
                    if (key.tag == "DELETE") {
                        handler.postDelayed(repeatRunnable, repeatInitialDelay)
                    }

                    // Long-press keys: schedule long-press detection
                    if (key.tag in longPressKeys) {
                        handler.postDelayed(longPressRunnable, 400L)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val last = gesturePath.lastOrNull()
                if (last == null || kotlin.math.abs(event.x - last.x) + kotlin.math.abs(event.y - last.y) > 8f) {
                    gesturePath.add(android.graphics.PointF(event.x, event.y))
                }

                val index = findKeyNearest(event.x, event.y)
                if (index != pressedKeyIndex) {
                    if (!isGesturing && gesturePath.size > 4) {
                        isGesturing = true
                        cancelAllCallbacks()
                    }
                    pressedKeyIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelAllCallbacks()

                if (isGesturing && gesturePath.size > 10) {
                    // Gesture completed
                    gestureListener?.onGestureCompleted(
                        ArrayList(gesturePath), keyRects, keys
                    )
                } else if (pressedKeyIndex >= 0 && !longPressHandled && !isRepeating) {
                    val key = keys[pressedKeyIndex]
                    if (key.tag !in immediateKeys) {
                        // Character keys and long-press keys: commit on ACTION_UP
                        // This is the GBoard pattern — commit happens on lift
                        keyListener?.onKeyPressed(key)
                    }
                    keyListener?.onKeyReleased(key)
                }

                gesturePath.clear()
                isGesturing = false
                pressedKeyIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelAllCallbacks() {
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(longPressRunnable)
        pendingKeyCommit?.let { handler.removeCallbacks(it) }
        pendingKeyCommit = null
    }

    /**
     * Find the nearest key using expanded touch targets and proximity scoring.
     * GBoard-style: keys have ~20% expanded hit areas, edge keys even more.
     * Uses distance-to-center scoring when touch is between keys.
     */
    private fun findKeyNearest(x: Float, y: Float): Int {
        // First: exact hit test
        for (i in keyRects.indices) {
            if (keyRects[i].contains(x, y)) return i
        }

        // Second: expanded hit test (20% margin)
        var bestIndex = -1
        var bestDist = Float.MAX_VALUE
        val expansion = keyMargin * 3  // ~20% expansion

        for (i in keyRects.indices) {
            val rect = keyRects[i]
            val expanded = RectF(
                rect.left - expansion,
                rect.top - expansion,
                rect.right + expansion,
                rect.bottom + expansion
            )
            if (expanded.contains(x, y)) {
                val dx = x - rect.centerX()
                val dy = y - rect.centerY()
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    bestIndex = i
                }
            }
        }
        return bestIndex
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEYBOARD LAYOUTS
    // ═════════════════════════════════════════════════════════════════════════

    data class KeyboardLayout(
        val keys: List<Key>,
        val numRows: Int,
        val totalCols: Float = 10f
    )

    companion object {
        fun createQwertyWithNumberRow(): KeyboardLayout {
            val keys = mutableListOf<Key>()

            // Row 0: Number row 1-0
            val nums = "1234567890"
            for (i in nums.indices) {
                keys.add(Key(nums[i].toString(), nums[i].toString(), 0, i.toFloat(), 1f))
            }

            // Row 1: Q W E R T Y U I O P
            val row1 = "qwertyuiop"
            for (i in row1.indices) {
                keys.add(Key(row1[i].toString(), row1[i].toString(), 1, i.toFloat(), 1f))
            }

            // Row 2: A S D F G H J K L
            val row2 = "asdfghjkl"
            for (i in row2.indices) {
                keys.add(Key(row2[i].toString(), row2[i].toString(), 2, i.toFloat() + 0.5f, 1f))
            }

            // Row 3: SHIFT Z X C V B N M DELETE
            keys.add(Key("⇧", "SHIFT", 3, 0f, 1.5f, KeyStyle.SPECIAL))
            val row3 = "zxcvbnm"
            for (i in row3.indices) {
                keys.add(Key(row3[i].toString(), row3[i].toString(), 3, 1.5f + i, 1f))
            }
            keys.add(Key("⌫", "DELETE", 3, 8.5f, 1.5f, KeyStyle.ACTION))

            // Row 4: ?123 | , | 🎤 | SPACE | . | ↵
            keys.add(Key("?123", "NUMBERS", 4, 0f, 1.5f, KeyStyle.SPECIAL))
            keys.add(Key(",", ",", 4, 1.5f, 1f))
            keys.add(Key("🎤", "MIC", 4, 2.5f, 1f, KeyStyle.MIC))
            keys.add(Key("", "SPACE", 4, 3.5f, 4f))
            keys.add(Key(".", ".", 4, 7.5f, 1f))
            keys.add(Key("↵", "ENTER", 4, 8.5f, 1.5f, KeyStyle.ACTION))

            return KeyboardLayout(keys, 5, 10f)
        }

        fun createQwertyLayout(): KeyboardLayout {
            val keys = mutableListOf<Key>()

            // Row 0: Q W E R T Y U I O P
            val row0 = "qwertyuiop"
            for (i in row0.indices) {
                keys.add(Key(row0[i].toString(), row0[i].toString(), 0, i.toFloat(), 1f))
            }

            // Row 1: A S D F G H J K L (centered with half-key padding)
            val row1 = "asdfghjkl"
            for (i in row1.indices) {
                keys.add(Key(row1[i].toString(), row1[i].toString(), 1, i.toFloat() + 0.5f, 1f))
            }

            // Row 2: SHIFT Z X C V B N M DELETE
            keys.add(Key("⇧", "SHIFT", 2, 0f, 1.5f, KeyStyle.SPECIAL))
            val row2 = "zxcvbnm"
            for (i in row2.indices) {
                keys.add(Key(row2[i].toString(), row2[i].toString(), 2, 1.5f + i, 1f))
            }
            keys.add(Key("⌫", "DELETE", 2, 8.5f, 1.5f, KeyStyle.ACTION))

            // Row 3: ?123 | 😀 | , | SPACE | . | 🎤 | ↵
            // Both emoji and mic are always visible
            keys.add(Key("?123", "NUMBERS", 3, 0f, 1.2f, KeyStyle.SPECIAL))
            keys.add(Key("😀", "EMOJI", 3, 1.2f, 1f, KeyStyle.SPECIAL))
            keys.add(Key(",", ",", 3, 2.2f, 0.8f))
            keys.add(Key("", "SPACE", 3, 3f, 4f))
            keys.add(Key(".", ".", 3, 7f, 0.8f))
            keys.add(Key("🎤", "MIC", 3, 7.8f, 1f, KeyStyle.MIC))
            keys.add(Key("↵", "ENTER", 3, 8.8f, 1.2f, KeyStyle.ACTION))

            return KeyboardLayout(keys, 4, 10f)
        }

        fun createNumbersLayout(): KeyboardLayout {
            val keys = mutableListOf<Key>()

            // Row 0: 1-0
            val row0 = "1234567890"
            for (i in row0.indices) {
                keys.add(Key(row0[i].toString(), row0[i].toString(), 0, i.toFloat(), 1f))
            }

            // Row 1: symbols
            val row1Labels = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
            for (i in row1Labels.indices) {
                keys.add(Key(row1Labels[i], row1Labels[i], 1, i.toFloat(), 1f))
            }

            // Row 2: more symbols + DELETE
            keys.add(Key("ES", "TRANSLATE", 2, 0f, 1.5f, KeyStyle.SPECIAL))
            val row2Labels = listOf("*", "\"", "'", ":", ";", "!", "?")
            for (i in row2Labels.indices) {
                keys.add(Key(row2Labels[i], row2Labels[i], 2, 1.5f + i, 1f))
            }
            keys.add(Key("⌫", "DELETE", 2, 8.5f, 1.5f, KeyStyle.ACTION))

            // Row 3: ABC | , | SPACE | . | ENTER
            keys.add(Key("ABC", "LETTERS", 3, 0f, 2f, KeyStyle.SPECIAL))
            keys.add(Key(",", ",", 3, 2f, 1f))
            keys.add(Key("", "SPACE", 3, 3f, 4f))
            keys.add(Key(".", ".", 3, 7f, 1f))
            keys.add(Key("↵", "ENTER", 3, 8f, 2f, KeyStyle.ACTION))

            return KeyboardLayout(keys, 4, 10f)
        }
    }
}
