package com.follett.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
            if (key.tag == "SHIFT") {
                longPressHandled = keyListener?.onKeyLongPressed(key) ?: false
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
    // TOUCH HANDLING
    // ═════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = findKeyAt(event.x, event.y)
                if (index >= 0) {
                    pressedKeyIndex = index
                    longPressHandled = false
                    isRepeating = false
                    repeatCount = 0
                    invalidate()

                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    val key = keys[index]

                    // Fire immediately for responsiveness
                    keyListener?.onKeyPressed(key)

                    // Schedule key repeat for DELETE
                    if (key.tag == "DELETE") {
                        handler.postDelayed(repeatRunnable, repeatInitialDelay)
                    }

                    // Schedule long press for SHIFT
                    if (key.tag == "SHIFT") {
                        handler.postDelayed(longPressRunnable, 500L)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = findKeyAt(event.x, event.y)
                if (index != pressedKeyIndex) {
                    cancelAllCallbacks()
                    pressedKeyIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelAllCallbacks()
                if (pressedKeyIndex >= 0 && !longPressHandled && !isRepeating) {
                    keyListener?.onKeyReleased(keys[pressedKeyIndex])
                }
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
    }

    private fun findKeyAt(x: Float, y: Float): Int {
        for (i in keyRects.indices) {
            if (keyRects[i].contains(x, y)) return i
        }
        return -1
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

            // Row 3: GBoard-style — ?123 | , | 🎤 | SPACE | . | ↵
            keys.add(Key("?123", "NUMBERS", 3, 0f, 1.5f, KeyStyle.SPECIAL))
            keys.add(Key(",", ",", 3, 1.5f, 1f))
            keys.add(Key("🎤", "MIC", 3, 2.5f, 1f, KeyStyle.MIC))
            keys.add(Key("", "SPACE", 3, 3.5f, 4f))
            keys.add(Key(".", ".", 3, 7.5f, 1f))
            keys.add(Key("↵", "ENTER", 3, 8.5f, 1.5f, KeyStyle.ACTION))

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
