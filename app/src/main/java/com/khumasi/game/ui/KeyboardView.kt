package com.khumasi.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.khumasi.game.core.LetterState
import com.khumasi.game.render.Palette
import kotlin.math.abs
import kotlin.math.min

/**
 * In-game Arabic keyboard drawn as one view (fast, no layout passes). Rows read right to left.
 * Letters fire on touch-down for quick typing with both thumbs; delete repeats when held;
 * the check key fires on release so a slip doesn't submit by accident.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val fonts: Fonts) : View(context) {

    interface Listener {
        fun onLetter(ch: Char)
        fun onDelete()
        fun onEnter()
    }

    var listener: Listener? = null
    var haptics = true
    var enterLabel = "تحقّق"
        set(value) { field = value; invalidate() }

    private class Key(val kind: Int, val ch: Char = ' ') {
        val rect = RectF()
        var pressed = false
    }

    private val rows: List<List<Key>> = listOf(
        "ضصثقفغعهخحج".map { Key(LETTER, it) },
        "شسيبلاتنمكط".map { Key(LETTER, it) },
        "ذءؤرىةوزظدئ".map { Key(LETTER, it) },
        listOf(Key(ENTER), Key(DELETE)),
    )
    private val allKeys = rows.flatten()
    private var states: Map<Char, LetterState> = emptyMap()
    private val pointerKeys = HashMap<Int, Key>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = fonts.bold
        color = UiColors.TEXT
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = UiColors.TEXT
    }
    private val iconPath = Path()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            listener?.onDelete()
            repeatHandler.postDelayed(this, 90)
        }
    }

    init {
        layoutDirection = LAYOUT_DIRECTION_RTL
        contentDescription = "لوحة المفاتيح"
    }

    fun setStates(map: Map<Char, LetterState>) {
        states = map
        invalidate()
    }

    /** Preferred height for a given width: four rows of comfortable keys. */
    fun preferredHeight(width: Int, maxHeight: Int): Int {
        val keyW = (width - dp(8f) * 2 - GAP_DP * 10 * resources.displayMetrics.density) / 11f
        val keyH = min(dp(56f), maxOf(dp(40f), keyW * 1.42f))
        val total = keyH * 4 + dp(GAP_DP) * 3 + dp(14f) * 2
        return min(total.toInt(), maxHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = dp(8f)
        val gap = dp(GAP_DP)
        val top = dp(14f)
        val keyH = (h - top * 2 - gap * 3) / 4f
        val letterW = (w - pad * 2 - gap * 10) / 11f
        for ((ri, row) in rows.withIndex()) {
            val y = top + ri * (keyH + gap)
            if (ri < 3) {
                // RTL: the first key of the row is on the right.
                for ((i, key) in row.withIndex()) {
                    val right = w - pad - i * (letterW + gap)
                    key.rect.set(right - letterW, y, right, y + keyH)
                }
            } else {
                val half = (w - pad * 2 - gap) / 2f
                row[0].rect.set(w - pad - half, y, w - pad, y + keyH) // check (right)
                row[1].rect.set(pad, y, pad + half, y + keyH)          // delete (left)
            }
        }
        textPaint.textSize = min(keyH * 0.5f, letterW * 0.78f)
        iconPaint.strokeWidth = dp(2.2f)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(9f)
        for (key in allKeys) {
            val r = key.rect
            val state = if (key.kind == LETTER) states[key.ch] ?: LetterState.UNKNOWN else LetterState.UNKNOWN
            val fill = when {
                key.kind == ENTER -> opaque(Palette.CORRECT)
                state == LetterState.CORRECT -> opaque(Palette.CORRECT)
                state == LetterState.PRESENT -> opaque(Palette.PRESENT)
                state == LetterState.ABSENT -> UiColors.KEY_ABSENT
                else -> UiColors.KEY
            }
            // soft depth: darker base offset, then the key face
            shadePaint.color = withAlpha(android.graphics.Color.BLACK, 0.35f)
            canvas.drawRoundRect(r.left, r.top + dp(2.5f), r.right, r.bottom + dp(2.5f), radius, radius, shadePaint)
            keyPaint.color = if (key.pressed) blend(fill, android.graphics.Color.WHITE, 0.22f) else fill
            val lift = if (key.pressed) dp(1.5f) else 0f
            canvas.drawRoundRect(r.left, r.top + lift, r.right, r.bottom + lift, radius, radius, keyPaint)

            val cx = r.centerX()
            val cy = r.centerY() + lift
            when (key.kind) {
                LETTER -> {
                    textPaint.color = if (state == LetterState.ABSENT) UiColors.KEY_ABSENT_TEXT else UiColors.TEXT
                    textPaint.typeface = fonts.bold
                    drawCentered(canvas, key.ch.toString(), cx, cy)
                }
                ENTER -> {
                    textPaint.color = UiColors.PRIMARY_TEXT
                    textPaint.typeface = fonts.heavy
                    val old = textPaint.textSize
                    textPaint.textSize = old * 0.82f
                    drawCentered(canvas, enterLabel, cx, cy)
                    textPaint.textSize = old
                }
                DELETE -> drawBackspace(canvas, cx, cy, r.height() * 0.42f)
            }
        }
    }

    private val bounds = android.graphics.Rect()
    private fun drawCentered(canvas: Canvas, s: String, cx: Float, cy: Float) {
        textPaint.getTextBounds(s, 0, s.length, bounds)
        canvas.drawText(s, cx, cy - bounds.exactCenterY(), textPaint)
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // Arrow-shaped outline pointing left (where the last typed letter is in RTL), with an ×.
        val w = size * 1.5f; val h = size
        iconPath.reset()
        iconPath.moveTo(cx - w / 2, cy)
        iconPath.lineTo(cx - w / 2 + h / 2, cy - h / 2)
        iconPath.lineTo(cx + w / 2, cy - h / 2)
        iconPath.lineTo(cx + w / 2, cy + h / 2)
        iconPath.lineTo(cx - w / 2 + h / 2, cy + h / 2)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        val xc = cx + w * 0.12f; val s = h * 0.2f
        canvas.drawLine(xc - s, cy - s, xc + s, cy + s, iconPaint)
        canvas.drawLine(xc - s, cy + s, xc + s, cy - s, iconPaint)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        // Rows are found by height; within a row the nearest key wins so gaps aren't dead zones.
        var best: Key? = null
        var bestRowDist = Float.MAX_VALUE
        for (row in rows) {
            val r0 = row[0].rect
            val dy = when {
                y < r0.top -> r0.top - y
                y > r0.bottom -> y - r0.bottom
                else -> 0f
            }
            if (dy < bestRowDist) {
                bestRowDist = dy
                best = row.minByOrNull { abs(it.rect.centerX() - x) }
            }
        }
        return if (bestRowDist < dp(24f)) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i)) ?: return true
                pointerKeys[event.getPointerId(i)] = key
                key.pressed = true
                if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (key.kind) {
                    LETTER -> listener?.onLetter(key.ch)
                    DELETE -> {
                        listener?.onDelete()
                        repeatHandler.removeCallbacks(repeatDelete)
                        repeatHandler.postDelayed(repeatDelete, 420)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val key = pointerKeys.remove(event.getPointerId(i))
                if (key != null) {
                    key.pressed = false
                    if (key.kind == DELETE) repeatHandler.removeCallbacks(repeatDelete)
                    if (key.kind == ENTER && keyAt(event.getX(i), event.getY(i)) === key) listener?.onEnter()
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatDelete)
                pointerKeys.values.forEach { it.pressed = false }
                pointerKeys.clear()
                invalidate()
            }
        }
        return true
    }

    /** Debug builds only: logs each key's center on screen so emulator tests can tap real keys. */
    fun logKeyPositions() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        for (key in allKeys) {
            val label = when (key.kind) { ENTER -> "ENTER"; DELETE -> "DELETE"; else -> key.ch.toString() }
            android.util.Log.i("KhumasiKeys", "KEY $label ${(loc[0] + key.rect.centerX()).toInt()} ${(loc[1] + key.rect.centerY()).toInt()}")
        }
    }

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatDelete)
        super.onDetachedFromWindow()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        return (0xFF shl 24) or ((ar + (br - ar) * t).toInt() shl 16) or ((ag + (bg - ag) * t).toInt() shl 8) or (ab + (bb - ab) * t).toInt()
    }

    companion object {
        private const val LETTER = 0
        private const val ENTER = 1
        private const val DELETE = 2
        private const val GAP_DP = 5f
    }
}
