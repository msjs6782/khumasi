package com.khumasi.game.render

import com.khumasi.game.core.LetterState
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Letter-tile state and animation clock for the 3D board. Lives on the GL thread; the UI sends
 * changes through GLSurfaceView.queueEvent. Times are SystemClock.uptimeMillis().
 */
internal class BoardAnimator(val rows: Int, val cols: Int) {

    class Tile {
        var letter: Char? = null
        var result: LetterState = LetterState.UNKNOWN
        var popAt = NEVER
        var removeAt = NEVER
        var removedLetter: Char? = null
        var flipAt = NEVER
        var bounceAt = NEVER
    }

    /** What the renderer needs for one tile this frame. */
    class Pose {
        var visible = false
        var letter: Char? = null
        var result: LetterState = LetterState.UNKNOWN
        var dx = 0f; var dy = 0f; var dz = 0f
        var scale = 1f
        var flipDeg = 0f
        var glow = 0f
    }

    val tiles = Array(rows * cols) { Tile() }
    var activeRow = 0
    private var shakeRow = -1
    private var shakeAt = NEVER
    var reduceMotion = false

    /** The latest time any running animation ends; the frame scheduler keeps drawing until then. */
    var busyUntil = 0L
        private set

    fun tile(row: Int, col: Int) = tiles[row * cols + col]

    fun setAll(guesses: List<String>, results: List<List<LetterState>>, input: String, active: Int) {
        for (t in tiles) {
            t.letter = null; t.result = LetterState.UNKNOWN
            t.popAt = NEVER; t.removeAt = NEVER; t.flipAt = NEVER; t.bounceAt = NEVER; t.removedLetter = null
        }
        for ((r, word) in guesses.withIndex()) {
            if (r >= rows) break
            for (c in 0 until min(cols, word.length)) {
                val t = tile(r, c)
                t.letter = word[c]
                t.result = results.getOrNull(r)?.getOrNull(c) ?: LetterState.UNKNOWN
                t.flipAt = 0L // already revealed
            }
        }
        if (active < rows) for (c in 0 until min(cols, input.length)) tile(active, c).letter = input[c]
        activeRow = active
        shakeRow = -1
    }

    fun typeLetter(row: Int, col: Int, ch: Char, now: Long) {
        if (row !in 0 until rows || col !in 0 until cols) return
        val t = tile(row, col)
        t.letter = ch; t.removeAt = NEVER; t.removedLetter = null
        t.popAt = if (reduceMotion) NEVER else now
        extend(now + POP_MS)
    }

    fun deleteLetter(row: Int, col: Int, now: Long) {
        if (row !in 0 until rows || col !in 0 until cols) return
        val t = tile(row, col)
        t.removedLetter = t.letter
        t.letter = null
        t.popAt = NEVER
        t.removeAt = if (reduceMotion) NEVER else now
        extend(now + REMOVE_MS)
    }

    /** Flips a submitted row one tile after another. Returns when the last tile finishes. */
    fun reveal(row: Int, word: String, results: List<LetterState>, now: Long): Long {
        if (row !in 0 until rows) return now
        for (c in 0 until cols) {
            val t = tile(row, c)
            t.letter = word.getOrNull(c)
            t.result = results.getOrElse(c) { LetterState.ABSENT }
            t.popAt = NEVER
            t.flipAt = if (reduceMotion) now else now + c * FLIP_STAGGER_MS
        }
        val end = if (reduceMotion) now else now + (cols - 1) * FLIP_STAGGER_MS + FLIP_MS
        extend(end)
        return end
    }

    fun shake(row: Int, now: Long) {
        if (reduceMotion) return
        shakeRow = row; shakeAt = now
        extend(now + SHAKE_MS)
    }

    fun bounce(row: Int, startAt: Long) {
        if (reduceMotion || row !in 0 until rows) return
        for (c in 0 until cols) tile(row, c).bounceAt = startAt + c * BOUNCE_STAGGER_MS
        extend(startAt + (cols - 1) * BOUNCE_STAGGER_MS + BOUNCE_MS)
    }

    private fun extend(until: Long) { busyUntil = max(busyUntil, until) }

    fun pose(row: Int, col: Int, now: Long, out: Pose) {
        val t = tile(row, col)
        out.dx = 0f; out.dy = 0f; out.dz = 0f; out.scale = 1f; out.flipDeg = 0f; out.glow = 0f
        out.result = t.result
        var letter = t.letter
        if (letter == null && t.removeAt != NEVER && now - t.removeAt < REMOVE_MS) {
            letter = t.removedLetter
            val p = (now - t.removeAt).toFloat() / REMOVE_MS
            out.scale = 1f - easeInCubic(p)
        }
        out.letter = letter
        out.visible = letter != null
        if (!out.visible) return

        if (t.popAt != NEVER && now - t.popAt < POP_MS) {
            val p = (now - t.popAt).toFloat() / POP_MS
            out.scale = 1f + 0.13f * sin(PI.toFloat() * p)
            out.dz += 0.55f * (1f - easeOutCubic(p))
        }
        if (t.flipAt != NEVER) {
            val p = ((now - t.flipAt).toFloat() / FLIP_MS).coerceIn(0f, 1f)
            out.flipDeg = 180f * easeInOutCubic(p)
            out.dz += 0.38f * sin(PI.toFloat() * p)
        }
        if (t.bounceAt != NEVER && now >= t.bounceAt) {
            val p = (now - t.bounceAt).toFloat() / BOUNCE_MS
            if (p < 1f) {
                out.dy += 0.42f * sin(PI.toFloat() * p) * (1f - p * 0.3f)
                out.glow = sin(PI.toFloat() * p)
            }
        }
        if (row == shakeRow && shakeAt != NEVER && now - shakeAt < SHAKE_MS) {
            val p = (now - shakeAt).toFloat() / SHAKE_MS
            out.dx += 0.2f * sin(p * PI.toFloat() * 7f) * (1f - p)
        }
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
        const val POP_MS = 150L
        const val REMOVE_MS = 120L
        const val FLIP_MS = 520L
        const val FLIP_STAGGER_MS = 230L
        const val SHAKE_MS = 480L
        const val BOUNCE_MS = 560L
        const val BOUNCE_STAGGER_MS = 95L

        fun revealDuration(cols: Int, reduceMotion: Boolean): Long =
            if (reduceMotion) 0L else (cols - 1) * FLIP_STAGGER_MS + FLIP_MS

        fun easeOutCubic(p: Float) = 1f - (1f - p).pow(3)
        fun easeInCubic(p: Float) = p * p * p
        fun easeInOutCubic(p: Float) = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f
    }
}
