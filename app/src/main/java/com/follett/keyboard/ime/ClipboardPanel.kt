package com.follett.keyboard.ime

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * ClipboardPanel — Recent clipboard items with tap-to-paste.
 *
 * Monitors the system clipboard and maintains a history of recent copies.
 * Users can tap any item to paste it, or pin items for persistent access.
 * Renders on Canvas for consistency with the keyboard architecture.
 */
class ClipboardPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface ClipboardListener {
        fun onClipboardItemSelected(text: String)
        fun onBackToKeyboard()
    }

    var clipboardListener: ClipboardListener? = null

    // ─── Data ────────────────────────────────────────────────────────────────
    private val clipHistory = mutableListOf<ClipItem>()
    private val maxItems = 20

    data class ClipItem(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        var isPinned: Boolean = false
    )

    // ─── Layout ──────────────────────────────────────────────────────────────
    private var density = 1f
    private var itemHeight = 0f
    private var headerHeight = 0f
    private val itemRects = mutableListOf<RectF>()

    // ─── Paints ──────────────────────────────────────────────────────────────
    private val paintBg = Paint().apply { color = 0xFF0D0D1A.toInt() }
    private val paintHeader = Paint().apply { color = 0xFF1A1A30.toInt() }
    private val paintItem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E38.toInt(); style = Paint.Style.FILL
    }
    private val paintItemBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A5C.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8FF.toInt()
    }
    private val paintHeaderText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0D0.toInt(); textAlign = Paint.Align.LEFT
    }
    private val paintBackBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt(); textAlign = Paint.Align.CENTER
    }
    private val paintPin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD700.toInt(); textAlign = Paint.Align.CENTER
    }
    private val paintEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666688.toInt(); textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        loadClipboardCurrent()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    fun refreshClipboard() {
        loadClipboardCurrent()
        invalidate()
    }

    fun addClipItem(text: String) {
        if (text.isBlank()) return
        // Remove duplicate
        clipHistory.removeAll { it.text == text && !it.isPinned }
        // Add to front
        clipHistory.add(0, ClipItem(text))
        // Trim
        while (clipHistory.size > maxItems) {
            val last = clipHistory.lastOrNull { !it.isPinned }
            if (last != null) clipHistory.remove(last) else break
        }
        invalidate()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MEASUREMENT
    // ═════════════════════════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        density = resources.displayMetrics.density
        itemHeight = 52f * density
        headerHeight = 44f * density
        paintText.textSize = 14f * density
        paintHeaderText.textSize = 13f * density
        paintBackBtn.textSize = 14f * density
        paintPin.textSize = 16f * density
        paintEmpty.textSize = 14f * density

        val totalHeight = headerHeight + (minOf(clipHistory.size, 5) * itemHeight).coerceAtLeast(itemHeight * 3)
        setMeasuredDimension(w, totalHeight.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateRects()
    }

    private fun recalculateRects() {
        itemRects.clear()
        val padding = 8f * density
        for (i in clipHistory.indices) {
            val y = headerHeight + i * itemHeight
            itemRects.add(RectF(padding, y + 2 * density, width - padding, y + itemHeight - 2 * density))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        // Header
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paintHeader)
        val headerY = headerHeight / 2 - (paintHeaderText.descent() + paintHeaderText.ascent()) / 2
        canvas.drawText("  Clipboard", 0f, headerY, paintHeaderText)
        canvas.drawText("ABC ←", width - 60f * density, headerY, paintBackBtn)

        if (clipHistory.isEmpty()) {
            val emptyY = headerHeight + itemHeight * 1.5f
            canvas.drawText("No clipboard items yet", width / 2f, emptyY, paintEmpty)
            return
        }

        // Items
        val cornerRadius = 8f * density
        for (i in clipHistory.indices) {
            if (i >= itemRects.size) break
            val rect = itemRects[i]
            val item = clipHistory[i]

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintItem)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paintItemBorder)

            // Text (truncated)
            val displayText = item.text.replace("\n", " ").take(50) + if (item.text.length > 50) "…" else ""
            val textY = rect.centerY() - (paintText.descent() + paintText.ascent()) / 2
            canvas.drawText(displayText, rect.left + 12 * density, textY, paintText)

            // Pin indicator
            if (item.isPinned) {
                canvas.drawText("📌", rect.right - 20 * density, textY, paintPin)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOUCH
    // ═════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            // Header tap — back button
            if (y < headerHeight) {
                if (x > width - 80 * density) {
                    clipboardListener?.onBackToKeyboard()
                }
                return true
            }

            // Item tap
            for (i in itemRects.indices) {
                if (itemRects[i].contains(x, y) && i < clipHistory.size) {
                    // Long-press area (right side) = toggle pin
                    if (x > itemRects[i].right - 40 * density) {
                        clipHistory[i].isPinned = !clipHistory[i].isPinned
                        invalidate()
                    } else {
                        clipboardListener?.onClipboardItemSelected(clipHistory[i].text)
                    }
                    return true
                }
            }
        }
        return true
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CLIPBOARD MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadClipboardCurrent() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank()) {
                    addClipItem(text)
                }
            }
        } catch (_: Exception) {}
    }
}
