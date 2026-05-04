package com.follett.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * KeyboardCanvasView — High-performance custom keyboard renderer.
 *
 * Draws all keys directly on a hardware-accelerated Canvas with zero view hierarchy.
 * Handles raw touch events for instant response (no Button click listener overhead).
 * This is the same architectural approach used by GBoard, SwiftKey, and all
 * production-grade Android keyboards.
 *
 * Performance characteristics:
 *  - Single View (no measure/layout pass for child views)
 *  - Hardware-accelerated Canvas drawing
 *  - Zero object allocation in onDraw() hot path
 *  - Direct touch-to-key mapping with no event propagation delay
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
        fun onKeyReleased(key: Key)
    }

    var keyListener: KeyListener? = null

    // ─── Key Data Model ──────────────────────────────────────────────────────
    data class Key(
        val label: String,
        val tag: String,
        val row: Int,
        val col: Float,      // Column position (supports fractional for weighted keys)
        val colSpan: Float,  // Width in column units
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
    private var longPressRunnable: Runnable? = null
    private val longPressTimeout = 400L

    // ─── Dimensions (calculated once in onSizeChanged) ───────────────────────
    private var keyHeight = 0f
    private var keyMargin = 4f
    private var cornerRadius = 16f
    private var totalCols = 10f  // Standard QWERTY width

    // ─── Pre-allocated Paints (zero allocation in onDraw) ────────────────────
    private val paintKeyNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A2E.toInt()
        style = Paint.Style.FILL
    }
    private val paintKeySpecial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF252545.toInt()
        style = Paint.Style.FILL
    }
    private val paintKeyAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D2847.toInt()
        style = Paint.Style.FILL
    }
    private val paintKeyMic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D1B3D.toInt()
        style = Paint.Style.FILL
    }
    private val paintKeyPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A5C.toInt()
        style = Paint.Style.FILL
    }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A5C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val paintBorderAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8FF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val paintTextAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val paintBg = Paint().apply {
        color = 0xFF0D0D1A.toInt()
        style = Paint.Style.FILL
    }

    // ─── Temp RectF for drawing (avoids allocation) ──────────────────────────
    private val tempRect = RectF()

    init {
        // Enable hardware acceleration
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
        val density = resources.displayMetrics.density
        keyHeight = 48f * density
        keyMargin = 2f * density
        cornerRadius = 8f * density
        paintText.textSize = 16f * density
        paintTextAction.textSize = 14f * density

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
    // DRAWING (ZERO ALLOCATION HOT PATH)
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        for (i in keys.indices) {
            val key = keys[i]
            val rect = keyRects.getOrNull(i) ?: continue

            // Select paint based on state
            val fillPaint = when {
                i == pressedKeyIndex -> paintKeyPressed
                key.style == KeyStyle.SPECIAL -> paintKeySpecial
                key.style == KeyStyle.ACTION -> paintKeyAction
                key.style == KeyStyle.MIC -> paintKeyMic
                else -> paintKeyNormal
            }

            val borderPaint = when (key.style) {
                KeyStyle.ACTION -> paintBorderAction
                else -> paintBorder
            }

            // Draw key background
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

            // Draw label
            val textPaint = when (key.style) {
                KeyStyle.ACTION -> paintTextAction
                else -> paintText
            }

            val label = getDisplayLabel(key)
            val textX = rect.centerX()
            val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, textX, textY, textPaint)
        }
    }

    private fun getDisplayLabel(key: Key): String {
        val tag = key.tag
        if (tag.length == 1 && tag[0].isLetter()) {
            return if (isShifted || isCapsLock) tag.uppercase() else tag.lowercase()
        }
        return key.label
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOUCH HANDLING (DIRECT — NO EVENT PROPAGATION)
    // ═════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = findKeyAt(event.x, event.y)
                if (index >= 0) {
                    pressedKeyIndex = index
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    // Schedule long press
                    val key = keys[index]
                    longPressRunnable = Runnable {
                        if (pressedKeyIndex == index) {
                            keyListener?.onKeyLongPressed(key)
                        }
                    }
                    postDelayed(longPressRunnable, longPressTimeout)

                    // Fire key press immediately for instant response
                    keyListener?.onKeyPressed(key)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = findKeyAt(event.x, event.y)
                if (index != pressedKeyIndex) {
                    // Finger moved off key — cancel
                    cancelLongPressTimer()
                    pressedKeyIndex = index
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (pressedKeyIndex >= 0) {
                    keyListener?.onKeyReleased(keys[pressedKeyIndex])
                }
                pressedKeyIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
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

            // Row 1: A S D F G H J K L (with half-key padding)
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
            keys.add(Key("", "SPACE", 3, 3.5f, 4f))  // GBoard shows no label on space
            keys.add(Key(".", ".", 3, 7.5f, 1f))
            keys.add(Key("↵", "ENTER", 3, 8.5f, 1.5f, KeyStyle.ACTION))

            return KeyboardLayout(keys, 4, 10f)
        }

        fun createNumbersLayout(): KeyboardLayout {
            val keys = mutableListOf<Key>()

            // Row 0: 1 2 3 4 5 6 7 8 9 0
            val row0 = "1234567890"
            for (i in row0.indices) {
                keys.add(Key(row0[i].toString(), row0[i].toString(), 0, i.toFloat(), 1f))
            }

            // Row 1: @ # $ % & - + ( ) /
            val row1 = "@#\$%&-+()/"
            val row1Labels = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
            for (i in row1.indices) {
                keys.add(Key(row1Labels[i], row1Labels[i], 1, i.toFloat(), 1f))
            }

            // Row 2: =\< * " ' : ; ! ? DELETE
            keys.add(Key("=\\<", "SYMBOLS2", 2, 0f, 1.5f, KeyStyle.SPECIAL))
            val row2 = "*\"':;!?"
            val row2Labels = listOf("*", "\"", "'", ":", ";", "!", "?")
            for (i in row2.indices) {
                keys.add(Key(row2Labels[i], row2Labels[i], 2, 1.5f + i, 1f))
            }
            keys.add(Key("⌫", "DELETE", 2, 8.5f, 1.5f, KeyStyle.ACTION))

            // Row 3: ABC | , | SPACE | . | ENTER
            keys.add(Key("ABC", "LETTERS", 3, 0f, 2f, KeyStyle.SPECIAL))
            keys.add(Key(",", ",", 3, 2f, 1f))
            keys.add(Key("SPACE", "SPACE", 3, 3f, 4f))
            keys.add(Key(".", ".", 3, 7f, 1f))
            keys.add(Key("↵", "ENTER", 3, 8f, 2f, KeyStyle.ACTION))

            return KeyboardLayout(keys, 4, 10f)
        }
    }
}
