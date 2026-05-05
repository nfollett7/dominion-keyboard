package com.follett.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller

/**
 * EmojiPanelView — Scrollable emoji grid with category tabs.
 *
 * Renders emoji directly on Canvas for performance consistency
 * with the keyboard. Supports vertical scrolling within categories
 * and horizontal category tab switching.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface EmojiListener {
        fun onEmojiSelected(emoji: String)
        fun onBackToKeyboard()
    }

    var emojiListener: EmojiListener? = null

    // ─── Layout ──────────────────────────────────────────────────────────────
    private var density = 1f
    private var columns = 8
    private var cellSize = 0f
    private var tabHeight = 0f
    private var panelHeight = 0f
    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var currentCategory = 0

    // ─── Paints ──────────────────────────────────────────────────────────────
    private val paintBg = Paint().apply { color = 0xFF0D0D1A.toInt() }
    private val paintTabBg = Paint().apply { color = 0xFF1A1A30.toInt() }
    private val paintTabActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt(); style = Paint.Style.FILL
    }
    private val paintEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val paintTabEmoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val paintBackBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0D0.toInt(); textAlign = Paint.Align.CENTER
    }

    // ─── Scrolling ───────────────────────────────────────────────────────────
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollOffset = (scrollOffset + dy).coerceIn(0f, maxScroll)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollOffset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll.toInt())
            invalidate()
            return true
        }
    })

    // ─── Emoji Data ──────────────────────────────────────────────────────────
    private val categories = listOf(
        EmojiCategory("😀", SMILEYS),
        EmojiCategory("👋", PEOPLE),
        EmojiCategory("🐶", ANIMALS),
        EmojiCategory("🍕", FOOD),
        EmojiCategory("⚽", ACTIVITIES),
        EmojiCategory("🚗", TRAVEL),
        EmojiCategory("💡", OBJECTS),
        EmojiCategory("❤️", SYMBOLS)
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MEASUREMENT
    // ═════════════════════════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        density = resources.displayMetrics.density
        cellSize = w.toFloat() / columns
        tabHeight = 44f * density
        panelHeight = 260f * density

        paintEmoji.textSize = 26f * density
        paintTabEmoji.textSize = 20f * density
        paintBackBtn.textSize = 14f * density

        setMeasuredDimension(w, (panelHeight + tabHeight).toInt())
        recalculateMaxScroll()
    }

    private fun recalculateMaxScroll() {
        val emojis = categories.getOrNull(currentCategory)?.emojis ?: return
        val rows = (emojis.size + columns - 1) / columns
        val contentHeight = rows * cellSize
        maxScroll = (contentHeight - panelHeight + tabHeight).coerceAtLeast(0f)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        // Category tabs
        drawCategoryTabs(canvas)

        // Emoji grid (clipped to panel area)
        canvas.save()
        canvas.clipRect(0f, tabHeight, width.toFloat(), height.toFloat())
        drawEmojiGrid(canvas)
        canvas.restore()
    }

    private fun drawCategoryTabs(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), tabHeight, paintTabBg)

        val tabWidth = (width.toFloat() - 50f * density) / categories.size
        val backBtnWidth = 50f * density

        // Back button (ABC)
        val backY = tabHeight / 2 - (paintBackBtn.descent() + paintBackBtn.ascent()) / 2
        canvas.drawText("ABC", backBtnWidth / 2, backY, paintBackBtn)

        // Category tabs
        for (i in categories.indices) {
            val x = backBtnWidth + i * tabWidth
            if (i == currentCategory) {
                val indicator = RectF(x + 4 * density, tabHeight - 3 * density, x + tabWidth - 4 * density, tabHeight)
                canvas.drawRoundRect(indicator, 2f * density, 2f * density, paintTabActive)
            }
            val textY = tabHeight / 2 - (paintTabEmoji.descent() + paintTabEmoji.ascent()) / 2
            canvas.drawText(categories[i].icon, x + tabWidth / 2, textY, paintTabEmoji)
        }
    }

    private fun drawEmojiGrid(canvas: Canvas) {
        val emojis = categories.getOrNull(currentCategory)?.emojis ?: return
        val textY = cellSize / 2 - (paintEmoji.descent() + paintEmoji.ascent()) / 2

        for (i in emojis.indices) {
            val col = i % columns
            val row = i / columns
            val x = col * cellSize + cellSize / 2
            val y = tabHeight + row * cellSize + textY - scrollOffset

            if (y > tabHeight - cellSize && y < height + cellSize) {
                canvas.drawText(emojis[i], x, y, paintEmoji)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOUCH
    // ═════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            // Tab area
            if (y < tabHeight) {
                val backBtnWidth = 50f * density
                if (x < backBtnWidth) {
                    emojiListener?.onBackToKeyboard()
                    return true
                }
                val tabWidth = (width.toFloat() - backBtnWidth) / categories.size
                val tabIndex = ((x - backBtnWidth) / tabWidth).toInt().coerceIn(0, categories.size - 1)
                if (tabIndex != currentCategory) {
                    currentCategory = tabIndex
                    scrollOffset = 0f
                    recalculateMaxScroll()
                    invalidate()
                }
                return true
            }

            // Emoji grid tap
            val gridY = y - tabHeight + scrollOffset
            val col = (x / cellSize).toInt()
            val row = (gridY / cellSize).toInt()
            val index = row * columns + col
            val emojis = categories.getOrNull(currentCategory)?.emojis
            if (emojis != null && index in emojis.indices) {
                emojiListener?.onEmojiSelected(emojis[index])
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            invalidate()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EMOJI DATA
    // ═════════════════════════════════════════════════════════════════════════

    data class EmojiCategory(val icon: String, val emojis: List<String>)

    companion object {
        val SMILEYS = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
            "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙",
            "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫",
            "🤔", "🫡", "🤐", "🤨", "😐", "😑", "😶", "🫥", "😏", "😒",
            "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒",
            "🤕", "🤢", "🤮", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳",
            "🥸", "😎", "🤓", "🧐", "😕", "🫤", "😟", "🙁", "😮", "😯",
            "😲", "😳", "🥺", "🥹", "😦", "😧", "😨", "😰", "😥", "😢",
            "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤",
            "😡", "😠", "🤬", "😈", "👿", "💀", "☠️", "💩", "🤡", "👹"
        )

        val PEOPLE = listOf(
            "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "👌",
            "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉",
            "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛",
            "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️", "💅",
            "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠",
            "👀", "👁️", "👅", "👄", "🫦", "👶", "🧒", "👦", "👧", "🧑",
            "👱", "👨", "🧔", "👩", "🧓", "👴", "👵", "🙍", "🙎", "🙅"
        )

        val ANIMALS = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨",
            "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒",
            "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇",
            "🐺", "🐗", "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌", "🐞",
            "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️", "🦂", "🐢", "🐍",
            "🦎", "🦖", "🦕", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠"
        )

        val FOOD = listOf(
            "🍕", "🍔", "🍟", "🌭", "🍿", "🧈", "🥞", "🧇", "🥓", "🥩",
            "🍗", "🍖", "🦴", "🌮", "🌯", "🫔", "🥙", "🧆", "🥚", "🍳",
            "🥘", "🍲", "🫕", "🥣", "🥗", "🍿", "🧈", "🍱", "🍘", "🍙",
            "🍚", "🍛", "🍜", "🍝", "🍠", "🍢", "🍣", "🍤", "🍥", "🥮",
            "🍡", "🥟", "🥠", "🥡", "🦀", "🦞", "🦐", "🦑", "🦪", "🍦",
            "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🥧", "🍫", "🍬"
        )

        val ACTIVITIES = listOf(
            "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
            "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳",
            "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷",
            "⛸️", "🥌", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "🤺",
            "⛹️", "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🤽", "🚣", "🧗",
            "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🏵️", "🎗️"
        )

        val TRAVEL = listOf(
            "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
            "🛻", "🚚", "🚛", "🚜", "🏍️", "🛵", "🚲", "🛴", "🛺", "🚨",
            "🚔", "🚍", "🚘", "🚖", "🚡", "🚠", "🚟", "🚃", "🚋", "🚞",
            "🚝", "🚄", "🚅", "🚈", "🚂", "🚆", "🚇", "🚊", "🚉", "✈️",
            "🛫", "🛬", "🛩️", "💺", "🛰️", "🚀", "🛸", "🚁", "🛶", "⛵",
            "🚤", "🛥️", "🛳️", "⛴️", "🚢", "🗼", "🏰", "🏯", "🏟️", "🎡"
        )

        val OBJECTS = listOf(
            "💡", "🔦", "🕯️", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️",
            "💾", "💿", "📀", "📷", "📸", "📹", "🎥", "📽️", "🎞️", "📞",
            "☎️", "📟", "📠", "📺", "📻", "🎙️", "🎚️", "🎛️", "🧭", "⏱️",
            "⏲️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋", "🪫", "🔌", "💰",
            "🪙", "💴", "💵", "💶", "💷", "💸", "💳", "🧾", "💹", "✉️",
            "📧", "📨", "📩", "📤", "📥", "📦", "📫", "📪", "📬", "📭"
        )

        val SYMBOLS = listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
            "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝",
            "✨", "⭐", "🌟", "💫", "🔥", "💥", "💢", "💦", "💨", "🕊️",
            "✅", "❌", "⭕", "❗", "❓", "‼️", "⁉️", "💯", "🔴", "🟠",
            "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔶", "🔷", "🔸",
            "🔹", "▪️", "▫️", "◾", "◽", "◼️", "◻️", "🟥", "🟧", "🟨"
        )
    }
}
