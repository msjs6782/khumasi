package com.khumasi.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.khumasi.game.core.ScrambleLevels
import com.khumasi.game.core.ScramblePuzzle
import com.khumasi.game.render.Palette
import kotlin.math.min
import kotlin.random.Random

/**
 * «الكلمة المبعثرة»: tap the mixed letters to put them in order. Shown over the 3D island; the
 * activity keeps the progress, shells and sounds through [Callbacks].
 */
@SuppressLint("ViewConstructor")
class ScrambleScreen(context: Context, private val fonts: Fonts, private val cb: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        fun onClose()
        /** Records the stage; returns the shells earned. */
        fun onSolved(stars: Int): Int
        fun onNext()
        /** Pays for a power-up; false (with a message shown) if there are not enough shells. */
        fun pay(cost: Int): Boolean
        fun shells(): Int
        fun sound(kind: Sound)
    }

    enum class Sound { TAP, BACK, WRONG, HINT, SOLVED }

    private var puzzle: ScramblePuzzle? = null
    private var stage = 1
    private var letters = CharArray(0)
    private var used = BooleanArray(0)
    private var slots = IntArray(0)
    private var locked = BooleanArray(0)
    private var hints = 0
    private var mistakes = 0
    private var solved = false
    private var shuffles = 0

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val shellsView: TextView
    private val stageView: TextView
    private val categoryView: TextView
    private val slotRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
    private val letterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
    private val message: TextView
    private val tools: LinearLayout
    private val hintBtn: TextView
    private val skipBtn: TextView
    private val success: LinearLayout
    private val successTitle: TextView
    private val successStars: TextView
    private val successNote: TextView

    init {
        layoutDirection = LAYOUT_DIRECTION_RTL
        isClickable = true // nothing reaches the game underneath
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x660E1626, 0x110E1626, 0xD90E1626.toInt(), 0xF50E1626.toInt()))

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(context.label("🔀 الكلمة المبعثرة", 24f, fonts.heavy, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            setShadowLayer(dp(6f), 0f, dp(1.5f), 0x99000000.toInt())
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        shellsView = context.label("", 16f, fonts.bold, opaque(Palette.PRESENT)).apply { setShadowLayer(dp(4f), 0f, dp(1f), 0x99000000.toInt()) }
        head.addView(shellsView)
        head.addView(IconView(context, IconView.Kind.CLOSE, "رجوع إلى خماسي").apply { setOnClickListener { cb.onClose() } },
            LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply { marginStart = dpi(8f) })
        content.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        stageView = context.label("", 17f, fonts.bold, Color.WHITE).apply { setShadowLayer(dp(4f), 0f, dp(1f), 0x99000000.toInt()) }
        content.addView(stageView, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f)) // the island shows here

        categoryView = context.label("", 15f, fonts.bold, UiColors.TEXT, Gravity.CENTER).apply {
            background = rounded(Color.parseColor("#CC223250"), dp(14f))
            setPadding(dpi(14f), dpi(5f), dpi(14f), dpi(5f))
        }
        content.addView(categoryView, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dpi(16f) })
        content.addView(slotRow, LinearLayout.LayoutParams(MATCH, WRAP))
        message = context.label("", 15f, fonts.bold, UiColors.MUTED, Gravity.CENTER)
        content.addView(message, LinearLayout.LayoutParams(MATCH, dpi(40f)).apply { topMargin = dpi(6f) })
        content.addView(letterRow, LinearLayout.LayoutParams(MATCH, WRAP))

        tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        val shuffleBtn = toolButton("🔀 خلط") { shuffle() }
        hintBtn = toolButton("💡 حرف · ${ar(ScrambleLevels.HINT_COST)}🐚") { hint() }
        skipBtn = toolButton("⏭️ تخطٍّ · ${ar(ScrambleLevels.SKIP_COST)}🐚") { skip() }
        tools.addView(shuffleBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        tools.addView(hintBtn, LinearLayout.LayoutParams(0, WRAP, 1.25f).apply { marginStart = dpi(8f) })
        tools.addView(skipBtn, LinearLayout.LayoutParams(0, WRAP, 1.25f).apply { marginStart = dpi(8f) })
        content.addView(tools, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(26f) })

        success = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
        }
        successTitle = context.label("", 26f, fonts.heavy, Color.WHITE, Gravity.CENTER)
        successStars = context.label("", 34f, fonts.heavy, opaque(Palette.PRESENT), Gravity.CENTER)
        successNote = context.label("", 15f, fonts.bold, UiColors.MUTED, Gravity.CENTER)
        success.addView(successTitle)
        success.addView(successStars)
        success.addView(successNote)
        success.addView(context.label("المرحلة التالية", 19f, fonts.bold, Color.WHITE, Gravity.CENTER).apply {
            background = rounded(UiColors.PRIMARY, dp(16f))
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            setOnClickListener { cb.onNext() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(14f) })
        content.addView(success, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(18f) })

        content.setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(18f))
        addView(content, LayoutParams(MATCH, MATCH))
    }

    private fun toolButton(label: String, onClick: () -> Unit): TextView =
        context.label(label, 15f, fonts.bold, UiColors.TEXT, Gravity.CENTER).apply {
            background = rounded(Color.parseColor("#E61A2740"), dp(16f), UiColors.CARD_LINE, dpi(1.5f))
            setPadding(dpi(8f), dpi(12f), dpi(8f), dpi(12f))
            setOnClickListener { onClick() }
        }

    /** Space for the system bars and the camera cutout. */
    fun setInsets(top: Int, bottom: Int, side: Int) = content.setPadding(dpi(16f) + side, top + dpi(4f), dpi(16f) + side, bottom + dpi(18f))

    fun show(stage: Int, p: ScramblePuzzle) {
        this.stage = stage
        puzzle = p
        letters = ScrambleLevels.scrambled(p, stage).toCharArray()
        used = BooleanArray(letters.size)
        slots = IntArray(letters.size) { -1 }
        locked = BooleanArray(letters.size)
        hints = 0; mistakes = 0; solved = false; shuffles = 0
        stageView.text = "المرحلة ${ar(stage)}"
        categoryView.text = "${p.category} · ${ar(p.word.length)} أحرف"
        message.text = "اضغط الحروف بالترتيب لتكوّن الكلمة"
        message.setTextColor(UiColors.MUTED)
        success.visibility = GONE
        tools.visibility = VISIBLE
        letterRow.visibility = VISIBLE
        refreshShells()
        build()
        if (letterRow.width == 0) post { build() }
    }

    fun refreshShells() { shellsView.text = "🐚 ${ar(cb.shells())}" }

    // ------------------------------------------------------------------ drawing

    private fun tileSize(): Int {
        val n = letters.size.coerceAtLeast(3)
        val avail = (if (width > 0) width else resources.displayMetrics.widthPixels) - dpi(32f) - dpi(8f) * (n - 1)
        return min(dpi(62f), avail / n)
    }

    private fun tile(ch: Char?, size: Int, fill: Int, ink: Int, stroke: Int? = null): TextView =
        context.label(ch?.toString() ?: "", size / resources.displayMetrics.scaledDensity * 0.52f, fonts.heavy, ink, Gravity.CENTER).apply {
            background = if (stroke != null && fill == Color.TRANSPARENT) rounded(fill, size * 0.2f, stroke, dpi(2f))
            else if (stroke != null) rounded(fill, size * 0.2f, stroke, dpi(3f))
            else rounded(fill, size * 0.2f)
            elevation = if (fill == Color.TRANSPARENT) 0f else dp(5f)
            includeFontPadding = false
        }

    private fun build() {
        val p = puzzle ?: return
        val size = tileSize()
        slotRow.removeAllViews()
        for (i in slots.indices) {
            val li = slots[i]
            val v = when {
                solved -> tile(p.word[i].takeIf { li < 0 } ?: letters[li], size, opaque(Palette.CORRECT), Color.WHITE)
                li < 0 -> tile(null, size, Color.TRANSPARENT, UiColors.TEXT, Color.parseColor("#88FFFFFF"))
                locked[i] -> tile(letters[li], size, opaque(Palette.TILE_IVORY), opaque(Palette.TILE_INK), opaque(Palette.CORRECT))
                else -> tile(letters[li], size, opaque(Palette.TILE_IVORY), opaque(Palette.TILE_INK))
            }
            v.contentDescription = if (li < 0) "خانة فارغة ${ar(i + 1)}" else "الحرف ${letters[li]} في الخانة ${ar(i + 1)}"
            v.setOnClickListener { takeBack(i) }
            slotRow.addView(v, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = dpi(8f) })
        }
        letterRow.removeAllViews()
        for (i in letters.indices) {
            val v = tile(letters[i], size, opaque(Palette.TILE_IVORY), opaque(Palette.TILE_INK))
            if (used[i]) { v.alpha = 0.18f; v.elevation = 0f }
            v.contentDescription = "الحرف ${letters[i]}"
            v.setOnClickListener { place(i) }
            letterRow.addView(v, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = dpi(8f) })
        }
        hintBtn.alpha = if (cb.shells() >= ScrambleLevels.HINT_COST) 1f else 0.5f
        skipBtn.alpha = if (cb.shells() >= ScrambleLevels.SKIP_COST) 1f else 0.5f
    }

    private fun pop(v: View?) {
        v ?: return
        v.scaleX = 0.6f; v.scaleY = 0.6f
        v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    // ------------------------------------------------------------------ play

    private fun place(li: Int) {
        if (solved || used[li]) return
        val slot = slots.indexOfFirst { it < 0 }
        if (slot < 0) return
        slots[slot] = li
        used[li] = true
        cb.sound(Sound.TAP)
        build()
        pop(slotRow.getChildAt(slot))
        if (slots.none { it < 0 }) postDelayed({ check() }, 160)
    }

    private fun takeBack(slot: Int) {
        if (solved || locked[slot] || slots[slot] < 0) return
        used[slots[slot]] = false
        slots[slot] = -1
        cb.sound(Sound.BACK)
        build()
    }

    private fun attempt(): String = slots.joinToString("") { letters[it].toString() }

    private fun check() {
        val p = puzzle ?: return
        if (solved || slots.any { it < 0 }) return
        val a = attempt()
        if (p.accepts(a)) { solve(a); return }
        mistakes++
        cb.sound(Sound.WRONG)
        message.text = "ليست هذه الكلمة، حاول ترتيبًا آخر"
        message.setTextColor(Color.parseColor("#FFFF8A80"))
        slotRow.animate().cancel()
        slotRow.translationX = 0f
        val d = dp(10f)
        slotRow.animate().translationX(d).setDuration(50).withEndAction {
            slotRow.animate().translationX(-d).setDuration(70).withEndAction {
                slotRow.animate().translationX(d / 2).setDuration(60).withEndAction {
                    slotRow.animate().translationX(0f).setDuration(50).start()
                }.start()
            }.start()
        }.start()
        postDelayed({
            if (solved) return@postDelayed
            for (i in slots.indices) if (!locked[i] && slots[i] >= 0) { used[slots[i]] = false; slots[i] = -1 }
            build()
        }, 520)
    }

    private fun shuffle() {
        if (solved) return
        val free = letters.indices.filter { !used[it] }
        if (free.size < 2) return
        val chars = free.map { letters[it] }
        var mixed = chars
        val r = Random(stage * 31L + ++shuffles)
        repeat(6) { mixed = chars.shuffled(r); if (mixed != chars) return@repeat }
        for ((k, i) in free.withIndex()) letters[i] = mixed[k]
        cb.sound(Sound.BACK)
        build()
        for (i in free) pop(letterRow.getChildAt(i))
    }

    /** Puts the right letter in the first position that is not right yet, and locks it. */
    private fun hint() {
        val p = puzzle ?: return
        if (solved) return
        val target = (p.word.indices).firstOrNull { i -> !locked[i] && (slots[i] < 0 || letters[slots[i]] != p.word[i]) } ?: return
        if (!cb.pay(ScrambleLevels.HINT_COST)) return
        // Free the slot if it holds a wrong letter.
        if (slots[target] >= 0) { used[slots[target]] = false; slots[target] = -1 }
        val need = p.word[target]
        var li = letters.indices.firstOrNull { !used[it] && letters[it] == need }
        if (li == null) {
            // The letter sits in another unlocked slot: take it from there.
            val j = slots.indices.first { !locked[it] && slots[it] >= 0 && letters[slots[it]] == need }
            li = slots[j]; slots[j] = -1; used[li] = false
        }
        slots[target] = li
        used[li] = true
        locked[target] = true
        hints++
        cb.sound(Sound.HINT)
        refreshShells()
        build()
        pop(slotRow.getChildAt(target))
        if (slots.none { it < 0 }) postDelayed({ check() }, 200)
    }

    private fun skip() {
        val p = puzzle ?: return
        if (solved || !cb.pay(ScrambleLevels.SKIP_COST)) return
        for (i in slots.indices) slots[i] = -1
        solved = true
        build()
        refreshShells()
        cb.onSolved(0)
        showSuccess("الكلمة: ${p.display}", "", "تخطّيت هذه المرحلة")
    }

    private fun solve(a: String) {
        val p = puzzle ?: return
        solved = true
        val stars = ScrambleLevels.stars(hints, mistakes)
        build()
        for (i in 0 until slotRow.childCount) {
            val v = slotRow.getChildAt(i)
            v.rotationY = 90f
            v.animate().rotationY(0f).setStartDelay(i * 70L).setDuration(220).start()
        }
        cb.sound(Sound.SOLVED)
        val shells = cb.onSolved(stars)
        refreshShells()
        val note = buildString {
            if (a != p.word) append("وجدت «$a»، والكلمة المقصودة «${p.display}». ")
            append("🐚 +${ar(shells)}")
        }
        postDelayed({ showSuccess(if (stars == 3) "رائع!" else "أحسنت!", "★".repeat(stars) + "☆".repeat(3 - stars), note) }, 200L + slotRow.childCount * 70L)
    }

    private fun showSuccess(title: String, stars: String, note: String) {
        successTitle.text = title
        successStars.text = stars
        successStars.visibility = if (stars.isEmpty()) GONE else VISIBLE
        successNote.text = note
        message.text = ""
        tools.visibility = GONE
        letterRow.visibility = GONE
        success.visibility = VISIBLE
        success.alpha = 0f
        success.translationY = dp(16f)
        success.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** Shows a line under the letters, e.g. a new badge. */
    fun note(text: String) {
        successNote.text = successNote.text.toString() + "\n" + text
    }

    // ------------------------------------------------------------------ debug-only demo helpers

    fun demoHint() = hint()

    fun demoSolve() {
        val p = puzzle ?: return
        for (i in p.word.indices) {
            val li = letters.indices.first { !used[it] && letters[it] == p.word[i] }
            slots[i] = li; used[li] = true
        }
        build()
        check()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
