package com.khumasi.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.khumasi.game.core.GameState
import com.khumasi.game.core.LetterState
import com.khumasi.game.render.Palette
import kotlin.math.min

/**
 * Flat board used only if the device cannot run the 3D renderer, so the game always stays
 * playable. Same layout and colors as the 3D board, without depth or animation.
 */
@SuppressLint("ViewConstructor")
class BoardView2D(context: Context, private val fonts: Fonts) : View(context) {
    private var state: GameState? = null
    var colorBlind = false
    private val region = RectF()
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonts.heavy }
    private val bgPaint = Paint()
    private val bounds = Rect()

    fun setRegion(left: Float, top: Float, right: Float, bottom: Float) { region.set(left, top, right, bottom); invalidate() }

    fun show(s: GameState) { state = s; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), opaque(Palette.SKY_ZENITH), opaque(Palette.SEA_DEEP), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val s = state ?: return
        if (region.isEmpty) return
        val rows = s.maxAttempts; val cols = s.length
        val gap = dp(6f)
        val size = min((region.width() - gap * (cols + 1)) / cols, (region.height() - gap * (rows + 1)) / rows)
        val boardW = cols * size + (cols - 1) * gap
        val boardH = rows * size + (rows - 1) * gap
        val left = region.centerX() - boardW / 2
        val top = region.centerY() - boardH / 2
        tilePaint.color = opaque(Palette.SLAB)
        canvas.drawRoundRect(left - gap * 2, top - gap * 2, left + boardW + gap * 2, top + boardH + gap * 2, dp(16f), dp(16f), tilePaint)
        textPaint.textSize = size * 0.55f
        for (r in 0 until rows) for (c in 0 until cols) {
            // RTL: column 0 is on the right.
            val x = left + (cols - 1 - c) * (size + gap)
            val y = top + r * (size + gap)
            val letter: Char?
            val st: LetterState?
            when {
                r < s.guesses.size -> { letter = s.guesses[r][c]; st = s.evaluations[r][c] }
                r == s.guesses.size && c < s.input.length -> { letter = s.input[c]; st = null }
                else -> { letter = null; st = null }
            }
            tilePaint.color = when {
                letter == null -> opaque(Palette.SOCKET)
                st == null -> opaque(Palette.TILE_IVORY)
                else -> opaque(Palette.forState(st, colorBlind))
            }
            canvas.drawRoundRect(x, y, x + size, y + size, size * 0.16f, size * 0.16f, tilePaint)
            if (letter != null) {
                textPaint.color = if (st == null) opaque(Palette.TILE_INK) else Color.WHITE
                val t = letter.toString()
                textPaint.getTextBounds(t, 0, 1, bounds)
                canvas.drawText(t, x + size / 2, y + size / 2 - bounds.exactCenterY(), textPaint)
            }
        }
    }
}
