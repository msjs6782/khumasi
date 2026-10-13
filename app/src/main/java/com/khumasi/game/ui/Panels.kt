package com.khumasi.game.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.khumasi.game.core.GameState
import com.khumasi.game.core.GameStatus
import com.khumasi.game.core.Island
import com.khumasi.game.core.LetterState
import com.khumasi.game.core.Progress
import com.khumasi.game.core.Solution
import com.khumasi.game.core.Stats
import com.khumasi.game.render.Palette

/** Content for the bottom sheets. */
class Panels(private val ctx: Context, private val fonts: Fonts) {

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun column(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(s: CharSequence, size: Float = 16f, bold: Boolean = false, color: Int = UiColors.TEXT, gravity: Int = Gravity.START): TextView =
        ctx.label(s, size, if (bold) fonts.bold else fonts.regular, color, gravity).apply {
            setLineSpacing(0f, 1.15f)
        }

    private fun LinearLayout.addSpaced(v: View, top: Float = 10f, width: Int = match) {
        addView(v, LinearLayout.LayoutParams(width, wrap).apply { topMargin = ctx.dpi(top) })
    }

    private fun miniTile(ch: Char, state: LetterState?): TextView = text(ch.toString(), 22f, bold = true, gravity = Gravity.CENTER).apply {
        val bg = if (state == null) Palette.TILE_IVORY else Palette.forState(state)
        background = rounded(opaque(bg), ctx.dp(8f))
        setTextColor(if (state == null) opaque(Palette.TILE_INK) else Color.WHITE)
        typeface = fonts.heavy
    }

    private fun tileRow(word: String, states: List<LetterState?>): LinearLayout = row().apply {
        for ((i, ch) in word.withIndex()) {
            addView(miniTile(ch, states[i]), LinearLayout.LayoutParams(ctx.dpi(42f), ctx.dpi(42f)).apply {
                marginStart = if (i == 0) 0 else ctx.dpi(5f)
            })
        }
    }

    fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        text(label, 18f, bold = true, gravity = Gravity.CENTER).apply {
            background = if (primary) rounded(UiColors.PRIMARY, ctx.dp(16f))
            else rounded(Color.TRANSPARENT, ctx.dp(16f), UiColors.CARD_LINE, ctx.dpi(1.5f))
            setPadding(ctx.dpi(16f), ctx.dpi(13f), ctx.dpi(16f), ctx.dpi(13f))
            setOnClickListener { onClick() }
        }

    // -------------------------------------------------------------- rules

    fun rules(): View = column().apply {
        addSpaced(text("اكتشف الكلمة المخفية المكوّنة من خمسة أحرف في ثماني محاولات أو أقل. اكتب كلمة صحيحة واضغط «تحقّق»، فتتلوّن الحروف لتدلّك:"), 4f)
        val g = LetterState.CORRECT; val p = LetterState.PRESENT; val a = LetterState.ABSENT
        addSpaced(tileRow("كتابة", listOf(g, null, null, null, null)), 14f, wrap)
        addSpaced(text("الأخضر: الكاف في الكلمة وفي مكانها الصحيح.", 15f, color = UiColors.MUTED), 6f)
        addSpaced(tileRow("مدرسة", listOf(null, p, null, null, null)), 14f, wrap)
        addSpaced(text("الكهرماني: الدال في الكلمة لكن في موضع آخر.", 15f, color = UiColors.MUTED), 6f)
        addSpaced(tileRow("سيارة", listOf(null, a, null, null, null)), 14f, wrap)
        addSpaced(text("الرمادي: الياء ليست في الكلمة، أو كُتبت مرات أكثر من وجودها فيها.", 15f, color = UiColors.MUTED), 6f)

        addSpaced(text("قواعد الحروف", 19f, bold = true), 22f)
        val rules = listOf(
            "التشكيل والتطويل لا يُحتسبان.",
            "أ إ آ ٱ تُكتب كلها «ا» في اللعبة، ويُحسب طول الكلمة بعد ذلك.",
            "ء ؤ ئ حروف مستقلة ولكلٍّ منها مفتاح.",
            "ة غير ه، و ى غير ي.",
            "تُقبل الكلمة وتصريفاتها (الجمع والمؤنث والأفعال المصرّفة)، دون «ال» التعريف أو الضمائر المتصلة أو الحروف الملتصقة مثل و، ف، ب، ل.",
            "الكلمة غير المقبولة لا تُحتسب محاولة.",
            "الحرف المكرر يُلوَّن بقدر عدد مرات وجوده في الكلمة: تُحتسب المواضع الصحيحة أولًا.",
        )
        for (r in rules) addSpaced(text("•  $r", 15f), 6f)
        addSpaced(text("التخمينات المقبولة مأخوذة من قاموس Ayaspell العربي المفتوح، والحلول منتقاة ومراجعة يدويًا.", 13f, color = UiColors.MUTED), 18f)
    }

    // -------------------------------------------------------------- settings

    class SettingsState(var reduceMotion: Boolean, var lightMode: Boolean, var haptics: Boolean)

    fun settings(s: SettingsState, version: String, onChanged: (SettingsState, lightModeChanged: Boolean) -> Unit): View = column().apply {
        fun toggle(title: String, desc: String, value: Boolean, set: (Boolean) -> Unit) {
            val r = row()
            val texts = column()
            texts.addView(text(title, 17f, bold = true))
            texts.addView(text(desc, 13f, color = UiColors.MUTED).apply { setPadding(0, ctx.dpi(2f), 0, 0) })
            r.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
            val sw = Switch(ctx).apply { isChecked = value; setOnCheckedChangeListener { _, v -> set(v) } }
            r.addView(sw, LinearLayout.LayoutParams(wrap, wrap))
            addSpaced(r, 14f)
        }
        toggle("تقليل الحركة", "إيقاف الانقلاب والارتداد والحركة المحيطة.", s.reduceMotion) {
            s.reduceMotion = it; onChanged(s, false)
        }
        toggle("الوضع الخفيف", "رسوم أبسط بلا ظلال ولا زجاج، للأجهزة المتوسطة.", s.lightMode) {
            s.lightMode = it; onChanged(s, true)
        }
        toggle("الاهتزاز", "اهتزاز خفيف عند الكتابة والنتائج.", s.haptics) {
            s.haptics = it; onChanged(s, false)
        }
        addSpaced(text("عن اللعبة", 19f, bold = true), 24f)
        addSpaced(text("خماسي — عالم الكلمات، الإصدار $version", 14f, color = UiColors.MUTED), 6f)
        addSpaced(text("الخط: Baloo Bhaijaan 2 © The Baloo 2 Project Authors، برخصة SIL Open Font License 1.1.", 13f, color = UiColors.MUTED), 6f)
        addSpaced(text("قائمة التخمينات: مشتقة من قاموس Ayaspell العربي © Mohamed Kebdani وTaha Zerrouki والمساهمون، برخصة MPL 1.1.", 13f, color = UiColors.MUTED), 6f)
    }

    // -------------------------------------------------------------- statistics

    fun stats(progress: Progress, highlightAttempt: Int? = null): View = column().apply {
        val s = progress.unassisted
        addSpaced(numbers(s), 6f)
        addSpaced(text("توزيع المحاولات", 17f, bold = true), 18f)
        addSpaced(distribution(s, highlightAttempt), 8f)
        val extra = row()
        extra.addView(text("الكلمات المكتشفة: ${ar(progress.discovered.size)}", 15f, color = UiColors.MUTED), LinearLayout.LayoutParams(0, wrap, 1f))
        extra.addView(text("الجزيرة: المستوى ${ar(progress.islandLevel)}", 15f, color = UiColors.MUTED))
        addSpaced(extra, 16f)
        if (progress.assisted.played > 0) {
            val a = progress.assisted
            addSpaced(text("جولات بمساعدة (منفصلة): لعبت ${ar(a.played)}، فزت ${ar(a.wins)}", 14f, color = UiColors.MUTED), 8f)
        }
    }

    private fun numbers(s: Stats): LinearLayout = row().apply {
        fun cell(value: String, label: String) {
            val c = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(text(value, 30f, bold = true, gravity = Gravity.CENTER).apply { typeface = fonts.heavy })
            c.addView(text(label, 13f, color = UiColors.MUTED, gravity = Gravity.CENTER))
            addView(c, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        cell(ar(s.played), "لعبت")
        cell(ar(s.winRatePercent) + "٪", "نسبة الفوز")
        cell(ar(s.currentStreak), "السلسلة")
        cell(ar(s.bestStreak), "أفضل سلسلة")
    }

    private fun distribution(s: Stats, highlight: Int?): LinearLayout = column().apply {
        val max = (s.distribution.maxOrNull() ?: 0).coerceAtLeast(1)
        for ((i, n) in s.distribution.withIndex()) {
            val r = row()
            r.addView(text(ar(i + 1), 15f, bold = true, gravity = Gravity.CENTER), LinearLayout.LayoutParams(ctx.dpi(22f), wrap))
            val bar = text(ar(n), 14f, bold = true, gravity = Gravity.END or Gravity.CENTER_VERTICAL).apply {
                val isNow = highlight == i + 1
                background = rounded(if (isNow) UiColors.PRIMARY else Color.parseColor("#FF34425E"), ctx.dp(6f))
                setPadding(ctx.dpi(8f), ctx.dpi(2f), ctx.dpi(8f), ctx.dpi(2f))
            }
            val weight = 0.08f + 0.92f * n / max
            val holder = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            holder.addView(bar, LinearLayout.LayoutParams(0, ctx.dpi(24f), weight))
            holder.addView(View(ctx), LinearLayout.LayoutParams(0, ctx.dpi(24f), 1f - weight + 0.0001f))
            r.addView(holder, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dpi(5f) })
        }
    }

    // -------------------------------------------------------------- round result

    fun resultTitle(state: GameState): String = if (state.status == GameStatus.WON) {
        when (state.guesses.size) {
            1 -> "عبقري!"; 2 -> "مذهل!"; 3 -> "رائع!"; 4 -> "ممتاز!"; 5 -> "أحسنت!"; 6 -> "جيد جدًا!"; 7 -> "نجحت!"
            else -> "في اللحظة الأخيرة!"
        }
    } else "انتهت المحاولات"

    /** Compact on purpose: the island stays visible above it and the buttons need no scrolling. */
    fun result(
        state: GameState,
        solution: Solution?,
        progress: Progress,
        levelUp: Boolean,
        onNext: () -> Unit,
        onShare: () -> Unit,
        onReport: () -> Unit,
    ): View = column().apply {
        val won = state.status == GameStatus.WON
        val display = solution?.display ?: state.answer
        if (!won) addSpaced(text("الكلمة كانت", 14f, color = UiColors.MUTED), 0f)

        val wordRow = row()
        wordRow.addView(text(display, 36f, bold = true).apply { typeface = fonts.heavy }, LinearLayout.LayoutParams(0, wrap, 1f))
        solution?.category?.let {
            wordRow.addView(text(it, 13f, bold = true, gravity = Gravity.CENTER).apply {
                background = rounded(Color.parseColor("#FF2C3B58"), ctx.dp(12f))
                setPadding(ctx.dpi(12f), ctx.dpi(4f), ctx.dpi(12f), ctx.dpi(4f))
            })
        }
        addSpaced(wordRow, 0f)
        solution?.meaning?.takeIf { it.isNotBlank() }?.let { addSpaced(text(it, 16f), 0f) }
        val seconds = (state.playedMs / 1000).toInt()
        val time = "${ar(seconds / 60)}:${ar(seconds % 60).padStart(2, '٠')}"
        val attempts = if (won) "المحاولات: ${ar(state.guesses.size)} من ${ar(state.maxAttempts)}" else "استُنفدت المحاولات الثماني"
        addSpaced(text("$attempts   •   الوقت: $time", 13f, color = UiColors.MUTED), 4f)

        val buttons = row()
        buttons.addView(button("كلمة جديدة", primary = true, onClick = onNext), LinearLayout.LayoutParams(0, wrap, 1.4f))
        buttons.addView(button("مشاركة", primary = false, onClick = onShare), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(buttons, 14f)

        val wins = progress.totalWins
        val island = when {
            levelUp -> "🏝️ مستوى جديد! جزيرتك الآن في المستوى ${ar(progress.islandLevel)}"
            Island.winsToNext(wins) > 0 -> "🏝️ جزيرتك: المستوى ${ar(progress.islandLevel)} — ${ar(Island.winsToNext(wins))} فوز للمستوى التالي"
            else -> "🏝️ جزيرتك اكتملت!"
        }
        addSpaced(text(island, 14f, bold = levelUp, color = if (levelUp) opaque(Palette.PRESENT) else UiColors.TEXT).apply {
            background = rounded(Color.parseColor("#FF223250"), ctx.dp(12f))
            setPadding(ctx.dpi(12f), ctx.dpi(7f), ctx.dpi(12f), ctx.dpi(7f))
        }, 12f)
        addSpaced(numbers(progress.unassisted), 12f)
        addSpaced(text("الإبلاغ عن هذه الكلمة", 13f, color = UiColors.MUTED, gravity = Gravity.CENTER).apply {
            setPadding(0, ctx.dpi(10f), 0, ctx.dpi(2f))
            setOnClickListener { onReport() }
        }, 2f)
    }

    // -------------------------------------------------------------- report a word

    fun report(word: String, onReason: (String) -> Unit): View = column().apply {
        addSpaced(text("ما المشكلة في كلمة «$word»؟ سنستبعدها من الحلول على جهازك فورًا، ونحفظ البلاغ لمراجعة القاموس.", 15f), 4f)
        for (reason in listOf("ليست كلمة عربية صحيحة", "نادرة أو غريبة جدًا", "لفظ غير لائق", "المعنى المعروض غير دقيق")) {
            addSpaced(button(reason, primary = false) { onReason(reason) }, 10f)
        }
    }
}
