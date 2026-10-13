package com.khumasi.game.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.khumasi.game.core.Achievements
import com.khumasi.game.core.Badge
import com.khumasi.game.core.GameState
import com.khumasi.game.core.Shells
import com.khumasi.game.core.GameStatus
import com.khumasi.game.core.Island
import com.khumasi.game.core.LetterState
import com.khumasi.game.core.Progress
import com.khumasi.game.core.Reward
import com.khumasi.game.core.Rewards
import com.khumasi.game.render.Themes
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

    class SettingsState(
        var sound: Boolean, var music: Boolean, var haptics: Boolean,
        var colorBlind: Boolean, var textSize: Int, var showTimer: Boolean,
        var reduceMotion: Boolean, var lightMode: Boolean, var hardMode: Boolean,
    )

    enum class Change { AUDIO, LOOK, TEXT, MOTION, LIGHT_MODE, GAMEPLAY }

    fun settings(
        s: SettingsState,
        version: String,
        onTutorial: () -> Unit,
        onChanged: (SettingsState, Change) -> Unit,
    ): View = column().apply {
        fun section(title: String) = addSpaced(text(title, 14f, bold = true, color = UiColors.ACCENT), 20f)
        fun toggle(title: String, desc: String, value: Boolean, change: Change, set: (Boolean) -> Unit) {
            val r = row()
            val texts = column()
            texts.addView(text(title, 17f, bold = true))
            texts.addView(text(desc, 13f, color = UiColors.MUTED).apply { setPadding(0, ctx.dpi(2f), 0, 0) })
            r.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
            val sw = Switch(ctx).apply { isChecked = value; setOnCheckedChangeListener { _, v -> set(v); onChanged(s, change) } }
            r.addView(sw, LinearLayout.LayoutParams(wrap, wrap))
            addSpaced(r, 12f)
        }
        section("الصوت")
        toggle("المؤثرات الصوتية", "أصوات قصيرة للكتابة وكشف الحروف والفوز.", s.sound, Change.AUDIO) { s.sound = it }
        toggle("الموسيقى", "لحن هادئ متكرر في الخلفية.", s.music, Change.AUDIO) { s.music = it }
        toggle("الاهتزاز", "اهتزاز خفيف عند الكتابة والنتائج.", s.haptics, Change.AUDIO) { s.haptics = it }

        section("العرض")
        toggle("وضع عمى الألوان", "برتقالي وأزرق عاليا التباين، مع رمز على كل قطعة: نقطة للصحيح ودائرة لما في موضع آخر.", s.colorBlind, Change.LOOK) { s.colorBlind = it }
        addSpaced(text("حجم النص", 17f, bold = true), 12f)
        val sizes = row()
        val labels = listOf("صغير", "متوسط", "كبير")
        val chips = ArrayList<TextView>()
        for ((i, l) in labels.withIndex()) {
            val chip = text(l, 15f, bold = true, gravity = Gravity.CENTER)
            chip.setPadding(0, ctx.dpi(9f), 0, ctx.dpi(9f))
            chips.add(chip)
            sizes.addView(chip, LinearLayout.LayoutParams(0, wrap, 1f).apply { if (i > 0) marginStart = ctx.dpi(8f) })
        }
        fun paintChips() = chips.forEachIndexed { i, c ->
            c.background = if (i == s.textSize) rounded(UiColors.PRIMARY, ctx.dp(12f)) else rounded(Color.TRANSPARENT, ctx.dp(12f), UiColors.CARD_LINE, ctx.dpi(1.5f))
        }
        paintChips()
        chips.forEachIndexed { i, c -> c.setOnClickListener { s.textSize = i; paintChips(); onChanged(s, Change.TEXT) } }
        addSpaced(sizes, 8f)
        toggle("إظهار المؤقت", "وقت الجولة تحت العنوان. لا ضغط زمني في الطور الأساسي.", s.showTimer, Change.LOOK) { s.showTimer = it }
        toggle("تقليل الحركة", "إيقاف الانقلاب والارتداد والقصاصات والحركة المحيطة.", s.reduceMotion, Change.MOTION) { s.reduceMotion = it }
        toggle("الوضع الخفيف", "رسوم أبسط بلا ظلال ولا زجاج، للأجهزة المتوسطة.", s.lightMode, Change.LIGHT_MODE) { s.lightMode = it }

        section("اللعب")
        toggle("الوضع الصعب", "يجب أن تُبقي الحروف الخضراء في أماكنها وتستخدم كل حرف كُشف. يبدأ من الجولة التالية.", s.hardMode, Change.GAMEPLAY) { s.hardMode = it }
        addSpaced(button("عرض الشرح التفاعلي", primary = false) { onTutorial() }, 14f)

        section("عن اللعبة")
        addSpaced(text("خماسي — عالم الكلمات، الإصدار $version", 14f, color = UiColors.MUTED), 6f)
        addSpaced(text("الخط: Baloo Bhaijaan 2 © The Baloo 2 Project Authors، برخصة SIL Open Font License 1.1.", 13f, color = UiColors.MUTED), 6f)
        addSpaced(text("قائمة التخمينات: مشتقة من قاموس Ayaspell العربي © Mohamed Kebdani وTaha Zerrouki والمساهمون، برخصة MPL 1.1.", 13f, color = UiColors.MUTED), 6f)
        addSpaced(text("الأصوات والموسيقى مولّدة برمجيًا داخل اللعبة.", 13f, color = UiColors.MUTED), 6f)
    }

    // -------------------------------------------------------------- statistics

    /** Today's daily challenge number, set by the activity (for the streak shown in statistics). */
    var todayDaily = 0

    fun stats(progress: Progress, highlightAttempt: Int? = null): View = column().apply {
        val s = progress.unassisted
        addSpaced(numbers(s), 6f)
        addSpaced(text("توزيع المحاولات", 17f, bold = true), 18f)
        addSpaced(distribution(s, highlightAttempt), 8f)
        val avg = s.averageAttempts
        val best = progress.bestWinMs / 1000
        val more = row()
        more.addView(text("متوسط المحاولات: ${if (avg > 0f) arDecimal(avg) else "—"}", 15f, color = UiColors.MUTED), LinearLayout.LayoutParams(0, wrap, 1f))
        more.addView(text("أسرع فوز: ${if (best > 0) "${ar((best / 60).toInt())}:${ar((best % 60).toInt()).padStart(2, '٠')}" else "—"}", 15f, color = UiColors.MUTED))
        addSpaced(more, 16f)
        val extra = row()
        extra.addView(text("الكلمات المكتشفة: ${ar(progress.discovered.size)}", 15f, color = UiColors.MUTED), LinearLayout.LayoutParams(0, wrap, 1f))
        extra.addView(text("الجزيرة: المستوى ${ar(progress.islandLevel)}", 15f, color = UiColors.MUTED))
        addSpaced(extra, 8f)
        if (progress.daily.played > 0) {
            addSpaced(text("تحدي اليوم", 17f, bold = true), 18f)
            addSpaced(numbers(progress.daily.copy(currentStreak = progress.dailyStreak(todayDaily))), 6f)
        }
        if (progress.rushPlayed > 0) {
            addSpaced(text("⏱️ سباق الوقت: أفضل نتيجة ${ar(progress.bestRush)} كلمات في ${ar(progress.rushPlayed)} سباقًا", 14f, color = UiColors.MUTED), 10f)
        }
        if (progress.friendPlayed > 0) {
            addSpaced(text("تحديات الأصدقاء: لعبت ${ar(progress.friendPlayed)}، فزت ${ar(progress.friendWins)}", 14f, color = UiColors.MUTED), 10f)
        }
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
        newRewards: List<Reward> = emptyList(),
        onIsland: () -> Unit = {},
        onExplain: (() -> Unit)? = null,
        onAnalyze: (() -> Unit)? = null,
        nextLabel: String = "كلمة جديدة",
        banner: String? = null,
        stats: Stats = progress.unassisted,
        shellsEarned: Int = 0,
        newBadges: List<Badge> = emptyList(),
        onBadges: () -> Unit = {},
    ): View = column().apply {
        val won = state.status == GameStatus.WON
        banner?.let {
            addSpaced(text(it, 14f, bold = true, color = UiColors.ACCENT).apply {
                background = rounded(Color.parseColor("#FF1E2F4F"), ctx.dp(12f))
                setPadding(ctx.dpi(12f), ctx.dpi(7f), ctx.dpi(12f), ctx.dpi(7f))
            }, 0f)
        }
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
        buttons.addView(button(nextLabel, primary = true, onClick = onNext), LinearLayout.LayoutParams(0, wrap, 1.4f))
        buttons.addView(button("مشاركة", primary = false, onClick = onShare), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(buttons, 14f)
        if (onExplain != null && onAnalyze != null) addSpaced(aiButtons(onExplain, onAnalyze), 8f)

        val wins = progress.totalWins
        val island = when {
            levelUp -> "🏝️ مستوى جديد! جزيرتك الآن في المستوى ${ar(progress.islandLevel)}"
            Island.winsToNext(wins) > 0 -> "🏝️ جزيرتك: المستوى ${ar(progress.islandLevel)} — ${ar(Island.winsToNext(wins))} فوز للمستوى التالي"
            else -> "🏝️ جزيرتك اكتملت!"
        }
        addSpaced(text("$island  ‹", 14f, bold = levelUp, color = if (levelUp) opaque(Palette.PRESENT) else UiColors.TEXT).apply {
            background = rounded(Color.parseColor("#FF223250"), ctx.dp(12f))
            setPadding(ctx.dpi(12f), ctx.dpi(7f), ctx.dpi(12f), ctx.dpi(7f))
            setOnClickListener { onIsland() }
        }, 12f)
        if (shellsEarned > 0) {
            addSpaced(text("🐚 +${ar(shellsEarned)} صدفة — لديك ${ar(progress.shells)}", 14f, bold = true, color = opaque(Palette.PRESENT)), 8f)
        }
        for (b in newBadges) addSpaced(badgeBanner(b, onBadges), 8f)
        for (r in newRewards) {
            val kind = if (r.kind == Reward.Kind.TILES) "سمة قطع جديدة" else "سماء جديدة"
            addSpaced(text("🎁 $kind: «${r.name}» — اخترها من المكافآت", 14f, bold = true, color = opaque(Palette.PRESENT)), 6f)
        }
        addSpaced(numbers(stats), 12f)
        addSpaced(text("الإبلاغ عن هذه الكلمة", 13f, color = UiColors.MUTED, gravity = Gravity.CENTER).apply {
            setPadding(0, ctx.dpi(10f), 0, ctx.dpi(2f))
            setOnClickListener { onReport() }
        }, 2f)
    }

    // -------------------------------------------------------------- rewards

    fun rewards(progress: Progress, tileId: String, skyId: String, onPick: (Reward) -> Unit): View = column().apply {
        val level = progress.islandLevel
        val wins = progress.totalWins
        val next = Island.winsToNext(wins)
        addSpaced(text(if (next > 0) "جزيرتك في المستوى ${ar(level)} من ${ar(Island.MAX_LEVEL)} — ${ar(next)} فوز للمستوى التالي"
            else "اكتملت جزيرتك! كل المكافآت متاحة.", 15f), 0f)
        val bar = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; background = rounded(Color.parseColor("#FF2C3B58"), ctx.dp(5f)) }
        val fill = View(ctx).apply { background = rounded(UiColors.PRIMARY, ctx.dp(5f)) }
        val frac = (level.toFloat() / Island.MAX_LEVEL).coerceIn(0.03f, 1f)
        bar.addView(fill, LinearLayout.LayoutParams(0, ctx.dpi(10f), frac))
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, ctx.dpi(10f), 1f - frac + 0.0001f))
        addSpaced(bar, 8f)
        fun group(title: String, kind: Reward.Kind, selected: String) {
            addSpaced(text(title, 17f, bold = true), 18f)
            for (r in Rewards.of(kind)) {
                val unlocked = Rewards.isUnlocked(r, level)
                val isOn = r.id == selected
                val card = row().apply {
                    background = rounded(if (isOn) Color.parseColor("#FF24405F") else Color.parseColor("#FF1D2A44"), ctx.dp(14f),
                        if (isOn) UiColors.PRIMARY else UiColors.CARD_LINE, ctx.dpi(if (isOn) 2f else 1f))
                    setPadding(ctx.dpi(12f), ctx.dpi(10f), ctx.dpi(12f), ctx.dpi(10f))
                    alpha = if (unlocked) 1f else 0.55f
                }
                card.addView(swatch(r), LinearLayout.LayoutParams(ctx.dpi(46f), ctx.dpi(46f)))
                val texts = column().apply { setPadding(ctx.dpi(12f), 0, ctx.dpi(8f), 0) }
                texts.addView(text(r.name, 17f, bold = true))
                texts.addView(text(r.description, 13f, color = UiColors.MUTED))
                card.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
                val status = when {
                    isOn -> "مختارة"
                    unlocked -> "اختر"
                    else -> "🔒 المستوى ${ar(r.unlockLevel)}"
                }
                card.addView(text(status, 13f, bold = true, color = if (isOn) opaque(Palette.CORRECT) else UiColors.MUTED, gravity = Gravity.CENTER))
                if (unlocked && !isOn) card.setOnClickListener { onPick(r) }
                addSpaced(card, 8f)
            }
        }
        group("سمات القطع", Reward.Kind.TILES, tileId)
        group("أجواء السماء", Reward.Kind.SKY, skyId)
    }

    private fun swatch(r: Reward): View = View(ctx).apply {
        background = if (r.kind == Reward.Kind.TILES) {
            val t = Themes.tile(r.id)
            rounded(opaque(t.face), ctx.dp(10f), opaque(t.ink), ctx.dpi(3f))
        } else {
            val k = Themes.sky(r.id)
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(opaque(k.zenith), opaque(k.horizon), opaque(k.seaShallow), opaque(k.seaDeep)),
            ).apply { cornerRadius = ctx.dp(10f) }
        }
    }

    // -------------------------------------------------------------- interactive tutorial

    /** Three short steps; each row of tiles flips in turn and every tile explains itself when tapped. */
    fun tutorial(reduceMotion: Boolean, onDone: () -> Unit): View = column().apply {
        val g = LetterState.CORRECT; val p = LetterState.PRESENT; val a = LetterState.ABSENT
        val steps = listOf(
            Triple("اكتب كلمة من خمسة أحرف ثم اضغط «تحقّق». لا تُحتسب الكلمة غير الموجودة في القاموس محاولة.", "كتابة", listOf(g, a, a, p, a)),
            Triple("الأخضر: الحرف في مكانه. الكهرماني: في الكلمة لكن في موضع آخر. الرمادي: ليس فيها. المس أي قطعة لتعرف السبب.", "مدرسة", listOf(g, p, a, a, g)),
            Triple("عندك ثماني محاولات. كل فوز ينمّي جزيرتك ويفتح سمات وأجواء جديدة. بالتوفيق!", "جزيرة", listOf(g, g, g, g, g)),
        )
        var index = 0
        val counter = text("", 13f, bold = true, color = UiColors.ACCENT)
        val body = text("", 16f)
        val tiles = row().apply { gravity = Gravity.CENTER }
        val explain = text("", 14f, color = UiColors.MUTED, gravity = Gravity.CENTER)
        val nextBtn = button("التالي", primary = true) {}
        addSpaced(counter, 0f); addSpaced(body, 6f)
        addSpaced(tiles, 16f); addSpaced(explain, 10f); addSpaced(nextBtn, 18f)

        fun reason(ch: Char, st: LetterState, word: String): String = when (st) {
            LetterState.CORRECT -> "«$ch» في الكلمة وفي مكانها الصحيح."
            LetterState.PRESENT -> "«$ch» في الكلمة لكن في موضع آخر."
            else -> "«$ch» ليست في الكلمة المخفية."
        }

        fun show() {
            val (msg, word, states) = steps[index]
            counter.text = "الخطوة ${ar(index + 1)} من ${ar(steps.size)}"
            body.text = msg
            explain.text = "المس قطعة"
            nextBtn.text = if (index == steps.lastIndex) "ابدأ اللعب" else "التالي"
            tiles.removeAllViews()
            for ((i, ch) in word.withIndex()) {
                val tile = miniTile(ch, null)
                tile.setOnClickListener {
                    tile.background = rounded(opaque(Palette.forState(states[i])), ctx.dp(8f))
                    tile.setTextColor(Color.WHITE)
                    explain.text = reason(ch, states[i], word)
                }
                tiles.addView(tile, LinearLayout.LayoutParams(ctx.dpi(50f), ctx.dpi(50f)).apply { if (i > 0) marginStart = ctx.dpi(6f) })
                val flip = Runnable {
                    tile.background = rounded(opaque(Palette.forState(states[i])), ctx.dp(8f))
                    tile.setTextColor(Color.WHITE)
                }
                if (reduceMotion) flip.run() else {
                    tile.animate().setStartDelay(350L + i * 220L).rotationX(90f).setDuration(160).withEndAction {
                        flip.run(); tile.rotationX = -90f
                        tile.animate().setStartDelay(0).rotationX(0f).setDuration(160).start()
                    }.start()
                }
            }
        }
        nextBtn.setOnClickListener { if (index < steps.lastIndex) { index++; show() } else onDone() }
        show()
    }

    // -------------------------------------------------------------- Gemini (personal key)

    class AiSettings(
        val masked: String?,
        val model: String,
        val usedToday: Int,
        val limit: Int,
        val onSave: (String) -> Unit,
        val onTest: ((String) -> Unit) -> Unit,
        val onClear: () -> Unit,
        val onModel: (String) -> Unit,
    )

    fun aiSettings(a: AiSettings): View = column().apply {
        addSpaced(text("الذكاء الاصطناعي (Gemini)", 14f, bold = true, color = UiColors.ACCENT), 20f)
        addSpaced(text("للتلميحات وشرح الكلمات وتحليل الجولة. يُحفظ مفتاحك مشفّرًا في جهازك فقط ولا يُنسخ احتياطيًا. اللعب نفسه لا يحتاج الإنترنت.", 13f, color = UiColors.MUTED), 6f)
        val status = text(if (a.masked != null) "مفعّل: ${a.masked}" else "غير مفعّل", 16f, bold = true,
            color = if (a.masked != null) opaque(Palette.CORRECT) else UiColors.MUTED)
        addSpaced(status, 10f)
        val input = android.widget.EditText(ctx).apply {
            hint = "الصق مفتاح Gemini API هنا"
            setHintTextColor(UiColors.MUTED)
            setTextColor(UiColors.TEXT)
            textSize = 15f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
            background = rounded(Color.parseColor("#FF131D31"), ctx.dp(12f), UiColors.CARD_LINE, ctx.dpi(1f))
            setPadding(ctx.dpi(12f), ctx.dpi(10f), ctx.dpi(12f), ctx.dpi(10f))
        }
        addSpaced(input, 8f)
        val result = text("", 13f, color = UiColors.MUTED)
        val buttons = row()
        buttons.addView(button("حفظ", primary = true) {
            val k = input.text.toString().trim()
            if (k.length < 20) { result.text = "يبدو أن المفتاح غير مكتمل"; return@button }
            a.onSave(k); input.setText("")
            status.text = "مفعّل: ••••${k.takeLast(4)}"; status.setTextColor(opaque(Palette.CORRECT))
            result.text = "حُفظ المفتاح. اضغط «اختبار» للتأكد."
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        buttons.addView(button("اختبار", primary = false) {
            result.text = "جارٍ الاختبار…"
            a.onTest { msg -> result.text = msg }
        }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(8f) })
        buttons.addView(button("حذف", primary = false) {
            a.onClear(); status.text = "غير مفعّل"; status.setTextColor(UiColors.MUTED); result.text = "حُذف المفتاح من الجهاز."
        }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(8f) })
        addSpaced(buttons, 8f)
        addSpaced(result, 6f)
        addSpaced(text("النموذج", 15f, bold = true), 12f)
        val model = android.widget.EditText(ctx).apply {
            setText(a.model)
            setTextColor(UiColors.TEXT)
            textSize = 14f
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
            background = rounded(Color.parseColor("#FF131D31"), ctx.dp(12f), UiColors.CARD_LINE, ctx.dpi(1f))
            setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(12f), ctx.dpi(8f))
            setOnFocusChangeListener { _, has -> if (!has) a.onModel(text.toString()) }
            setOnEditorActionListener { _, _, _ -> a.onModel(text.toString()); false }
        }
        addSpaced(model, 6f)
        addSpaced(text("إذا لم يتوفر هذا النموذج لمفتاحك، تجرّب اللعبة نماذج بديلة تلقائيًا. الاستخدام اليوم: ${ar(a.usedToday)} من ${ar(a.limit)} طلبًا.", 12f, color = UiColors.MUTED), 6f)
    }

    /** A titled block of AI (or fallback) text, filled in later. */
    class ReplyView(val root: View, val body: TextView, val note: TextView)

    fun replyView(intro: String): ReplyView {
        val c = column()
        if (intro.isNotEmpty()) c.addView(text(intro, 13f, color = UiColors.MUTED))
        val body = text("لحظة… ✨", 17f)
        val note = text("", 12f, color = UiColors.MUTED)
        c.addView(body, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dpi(8f) })
        c.addView(note, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.dpi(8f) })
        return ReplyView(c, body, note)
    }

    fun hintSheet(hints: List<String>, canAskMore: Boolean, assisted: Boolean, onMore: () -> Unit): Pair<View, ReplyView?> {
        val c = column()
        if (!assisted) c.addSpaced(text("التلميحات تجعل هذه الجولة «بمساعدة»، فتُحسب في إحصاءات منفصلة.", 13f, color = UiColors.MUTED), 0f)
        for ((i, h) in hints.withIndex()) {
            c.addSpaced(text("التلميح ${ar(i + 1)}", 13f, bold = true, color = UiColors.ACCENT), 12f)
            c.addSpaced(text(h, 17f), 4f)
        }
        var pending: ReplyView? = null
        if (canAskMore) {
            c.addSpaced(button(if (hints.isEmpty()) "اطلب تلميحًا" else "تلميح أقوى (${ar(hints.size + 1)} من ٣)", primary = true) { onMore() }, 16f)
        } else if (hints.size >= 3) {
            c.addSpaced(text("استخدمت التلميحات الثلاثة لهذه الجولة.", 13f, color = UiColors.MUTED), 14f)
        }
        return c to pending
    }

    fun aiButtons(onExplain: () -> Unit, onAnalyze: () -> Unit): View = row().apply {
        addView(button("✨ شرح الكلمة", primary = false, onClick = onExplain), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(button("✨ تحليل جولتي", primary = false, onClick = onAnalyze), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
    }

    // -------------------------------------------------------------- play modes (Phase 4)

    class ModesInfo(
        val current: String,
        val dailyNumber: Int,
        val dailyDone: Boolean,
        val dailyStreak: Int,
        val nextDailyIn: String,
        val hasFriendRound: Boolean,
        val bestRush: Int = 0,
        val shells: Int = 0,
        val badges: Int = 0,
    )

    private fun card(title: String, subtitle: String, selected: Boolean): LinearLayout = column().apply {
        background = rounded(if (selected) Color.parseColor("#FF223A5E") else Color.parseColor("#FF1A2740"), ctx.dp(18f),
            if (selected) UiColors.ACCENT else UiColors.CARD_LINE, ctx.dpi(if (selected) 2f else 1f))
        setPadding(ctx.dpi(16f), ctx.dpi(12f), ctx.dpi(16f), ctx.dpi(14f))
        addView(text(title, 19f, bold = true).apply { typeface = fonts.heavy })
        addSpaced(text(subtitle, 14f, color = UiColors.MUTED), 2f)
    }

    fun modes(
        info: ModesInfo,
        onDaily: () -> Unit,
        onClassic: () -> Unit,
        onFriendCreate: () -> Unit,
        onFriendEnter: () -> Unit,
        onFriendResume: () -> Unit,
        onRush: () -> Unit = {},
        onBadges: () -> Unit = {},
    ): View = column().apply {
        val top = row()
        top.addView(text("🐚 ${ar(info.shells)} صدفة", 15f, bold = true, color = opaque(Palette.PRESENT)), LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(text("🏅 الأوسمة ${ar(info.badges)}/${ar(Achievements.ALL.size)}  ‹", 15f, bold = true, color = UiColors.ACCENT).apply {
            setPadding(ctx.dpi(8f), ctx.dpi(6f), 0, ctx.dpi(6f))
            setOnClickListener { onBadges() }
        })
        addSpaced(top, 0f)
        val streak = if (info.dailyStreak > 0) "   🔥 ${ar(info.dailyStreak)}" else ""
        val daily = card("📅 تحدي اليوم ${ar(info.dailyNumber)}$streak",
            if (info.dailyDone) "أنهيت تحدي اليوم ✓ — التالي بعد ${info.nextDailyIn}"
            else "كلمة واحدة في اليوم، نفسها عند كل من يلعب. تحافظ على سلسلتك بالفوز كل يوم.",
            info.current == GameState.MODE_DAILY)
        daily.addSpaced(button(if (info.dailyDone) "عرض نتيجتي" else if (info.current == GameState.MODE_DAILY) "متابعة" else "العب الآن", primary = !info.dailyDone, onClick = onDaily), 10f)
        addSpaced(daily, 2f)

        val classic = card("🎲 اللعب الحر", "كلمات عشوائية بلا حدود، وتنمو جزيرتك مع كل فوز.", info.current == GameState.MODE_CLASSIC)
        if (info.current != GameState.MODE_CLASSIC) classic.addSpaced(button("العب", primary = false, onClick = onClassic), 10f)
        addSpaced(classic, 12f)

        val rush = card("⏱️ سباق الوقت" + if (info.bestRush > 0) "   🏁 ${ar(info.bestRush)}" else "",
            "ثلاث دقائق لتحل أكبر عدد من الكلمات، وكل كلمة تحلها تضيف ٢٠ ثانية.", info.current == GameState.MODE_RUSH)
        rush.addSpaced(button(if (info.current == GameState.MODE_RUSH) "متابعة السباق" else "ابدأ السباق", primary = false, onClick = onRush), 10f)
        addSpaced(rush, 12f)

        val friend = card("🤝 تحدي صديق", "اختر كلمة وأرسل رمزها لصديقك، أو الصق رمزًا وصلك.", info.current == GameState.MODE_FRIEND)
        val r = row()
        r.addView(button("أنشئ تحديًا", primary = false, onClick = onFriendCreate), LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button("أدخل رمزًا", primary = false, onClick = onFriendEnter), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        friend.addSpaced(r, 10f)
        if (info.hasFriendRound && info.current != GameState.MODE_FRIEND) friend.addSpaced(button("متابعة التحدي الحالي", primary = false, onClick = onFriendResume), 8f)
        addSpaced(friend, 12f)
    }

    private fun field(hint: String, ltr: Boolean): android.widget.EditText = android.widget.EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(UiColors.MUTED)
        setTextColor(UiColors.TEXT)
        textSize = 20f
        typeface = fonts.bold
        isSingleLine = true
        gravity = Gravity.CENTER
        textDirection = if (ltr) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
        background = rounded(Color.parseColor("#FF131D31"), ctx.dp(14f), UiColors.CARD_LINE, ctx.dpi(1f))
        setPadding(ctx.dpi(12f), ctx.dpi(12f), ctx.dpi(12f), ctx.dpi(12f))
    }

    /** [onCreate] returns an error message, or null when the challenge was made. */
    fun friendCreate(randomWord: () -> String, onCreate: (String) -> String?): View = column().apply {
        addSpaced(text("اكتب كلمة من خمسة أحرف ليخمّنها صديقك، أو اختر كلمة عشوائية. يصله رمز لا يُظهر الكلمة.", 15f), 2f)
        val input = field("الكلمة", ltr = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        addSpaced(input, 12f)
        val error = text("", 14f, color = Color.parseColor("#FFFF8A80"))
        val r = row()
        r.addView(button("كلمة عشوائية", primary = false) { input.setText(randomWord()); error.text = "" }, LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button("أنشئ وشارك", primary = true) { error.text = onCreate(input.text.toString()) ?: "" },
            LinearLayout.LayoutParams(0, wrap, 1.2f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(r, 10f)
        addSpaced(error, 8f)
        addSpaced(text("صديقك يحتاج تطبيق خماسي أيضًا. يفتحه ويلصق الرمز من «تحدي صديق ← أدخل رمزًا»، أو يشارك رسالتك مباشرة إلى خماسي.", 13f, color = UiColors.MUTED), 6f)
    }

    /** [onStart] returns an error message, or null when the round started. */
    fun friendEnter(prefill: String, onPaste: () -> String?, onStart: (String) -> String?): View = column().apply {
        addSpaced(text("الصق الرمز الذي وصلك (يبدأ بـ KH) أو الرسالة كاملة.", 15f), 2f)
        val input = field("KH-XXXX-XXXX-XXXXX", ltr = true).apply {
            setText(prefill)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            maxLines = 3
        }
        addSpaced(input, 12f)
        val error = text("", 14f, color = Color.parseColor("#FFFF8A80"))
        val r = row()
        r.addView(button("لصق", primary = false) { onPaste()?.let { input.setText(it) } }, LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button("ابدأ التحدي", primary = true) { error.text = onStart(input.text.toString()) ?: "" },
            LinearLayout.LayoutParams(0, wrap, 1.3f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(r, 10f)
        addSpaced(error, 8f)
    }

    // -------------------------------------------------------------- backup (sync without a server)

    fun backupSection(lastBackup: String?, onExport: () -> Unit, onImport: () -> Unit): View = column().apply {
        addSpaced(text("النسخ الاحتياطي والنقل", 14f, bold = true, color = UiColors.ACCENT), 20f)
        addSpaced(text("احفظ تقدمك وإحصاءاتك وإعداداتك في ملف (على Google Drive مثلًا) واسترجعه على هذا الهاتف أو هاتف آخر. مفتاح Gemini لا يُضمَّن.", 13f, color = UiColors.MUTED), 6f)
        lastBackup?.let { addSpaced(text("آخر نسخة: $it", 13f, color = UiColors.MUTED), 4f) }
        val r = row()
        r.addView(button("حفظ نسخة", primary = false, onClick = onExport), LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button("استرجاع", primary = false, onClick = onImport), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(r, 8f)
    }

    fun confirm(message: String, yes: String, onYes: () -> Unit, onNo: () -> Unit): View = column().apply {
        addSpaced(text(message, 16f), 2f)
        val r = row()
        r.addView(button(yes, primary = true, onClick = onYes), LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button("إلغاء", primary = false, onClick = onNo), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(r, 14f)
    }

    // -------------------------------------------------------------- fun update: shells, power-ups, race, badges

    private fun badgeBanner(b: Badge, onClick: () -> Unit): View = row().apply {
        background = rounded(Color.parseColor("#FF3A2E12"), ctx.dp(14f), opaque(Palette.PRESENT), ctx.dpi(1.5f))
        setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(12f), ctx.dpi(8f))
        addView(text(b.emoji, 28f, gravity = Gravity.CENTER))
        val c = column()
        c.addView(text("وسام جديد: ${b.title}", 16f, bold = true, color = opaque(Palette.PRESENT)))
        c.addView(text("${b.description} · +${ar(b.shells)} 🐚", 13f, color = UiColors.MUTED))
        addView(c, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        setOnClickListener { onClick() }
    }

    /** All badges: earned ones in color, the rest dimmed with how to get them. */
    fun badges(progress: Progress): View = column().apply {
        val have = progress.badges
        addSpaced(text("حصلت على ${ar(have.size)} من ${ar(Achievements.ALL.size)} وسامًا", 15f, color = UiColors.MUTED), 0f)
        for (pair in Achievements.ALL.chunked(2)) {
            val r = row()
            for ((i, b) in pair.withIndex()) {
                val got = b.id in have
                val cell = column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = rounded(if (got) Color.parseColor("#FF243656") else Color.parseColor("#FF161F33"), ctx.dp(16f),
                        if (got) opaque(Palette.PRESENT) else UiColors.CARD_LINE, ctx.dpi(1f))
                    setPadding(ctx.dpi(8f), ctx.dpi(10f), ctx.dpi(8f), ctx.dpi(10f))
                    alpha = if (got) 1f else 0.55f
                    addView(text(if (got) b.emoji else "🔒", 30f, gravity = Gravity.CENTER))
                    addView(text(b.title, 15f, bold = true, gravity = Gravity.CENTER))
                    addView(text(b.description, 12f, color = UiColors.MUTED, gravity = Gravity.CENTER))
                }
                r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = ctx.dpi(10f) })
            }
            if (pair.size == 1) r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ctx.dpi(10f) })
            addSpaced(r, 10f)
        }
    }

    class HelpInfo(
        val shells: Int,
        val canReveal: Boolean,
        val canEliminate: Boolean,
        val revealNote: String?,
        val eliminateNote: String?,
        val hints: List<String>,
        val canAskMore: Boolean,
        val hintsAllowed: Boolean,
        val assisted: Boolean,
    )

    private fun powerRow(emoji: String, title: String, desc: String, cost: Int, enabled: Boolean, note: String?, onBuy: () -> Unit): View = row().apply {
        background = rounded(Color.parseColor("#FF1A2740"), ctx.dp(16f), UiColors.CARD_LINE, ctx.dpi(1f))
        setPadding(ctx.dpi(12f), ctx.dpi(10f), ctx.dpi(12f), ctx.dpi(10f))
        addView(text(emoji, 26f, gravity = Gravity.CENTER))
        val c = column()
        c.addView(text(title, 16f, bold = true))
        c.addView(text(note ?: desc, 12f, color = UiColors.MUTED))
        addView(c, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f); marginEnd = ctx.dpi(8f) })
        val buy = button("${ar(cost)} 🐚", primary = enabled) { if (enabled) onBuy() }.apply {
            textSize = 15f
            setPadding(ctx.dpi(14f), ctx.dpi(9f), ctx.dpi(14f), ctx.dpi(9f))
            alpha = if (enabled) 1f else 0.45f
        }
        addView(buy)
    }

    /** The 💡 sheet: power-ups bought with shells, then the graded hints. */
    fun helpSheet(h: HelpInfo, onReveal: () -> Unit, onEliminate: () -> Unit, onMore: () -> Unit): View = column().apply {
        addSpaced(text("لديك ${ar(h.shells)} 🐚", 20f, bold = true, color = opaque(Palette.PRESENT)).apply { typeface = fonts.heavy }, 0f)
        addSpaced(text("تكسب الأصداف بالفوز: كلما قلّت محاولاتك زادت. أي مساعدة تجعل الجولة «بمساعدة».", 13f, color = UiColors.MUTED), 4f)
        addSpaced(powerRow("🔦", "كشف حرف", "يظهر حرف في مكانه الصحيح", Shells.REVEAL_COST, h.canReveal, h.revealNote, onReveal), 12f)
        addSpaced(powerRow("🧹", "إزالة حروف خاطئة", "يُطفئ ${ar(Shells.ELIMINATE_COUNT)} حروف ليست في الكلمة", Shells.ELIMINATE_COST, h.canEliminate, h.eliminateNote, onEliminate), 8f)
        if (!h.hintsAllowed) return@apply
        addSpaced(text("تلميحات بالكلمات", 14f, bold = true, color = UiColors.ACCENT), 18f)
        for ((i, t) in h.hints.withIndex()) {
            addSpaced(text("التلميح ${ar(i + 1)}", 13f, bold = true, color = UiColors.MUTED), 8f)
            addSpaced(text(t, 17f), 2f)
        }
        if (h.canAskMore) {
            addSpaced(button(if (h.hints.isEmpty()) "اطلب تلميحًا (مجاني)" else "تلميح أقوى (${ar(h.hints.size + 1)} من ٣)", primary = false) { onMore() }, 10f)
        } else if (h.hints.size >= 3) {
            addSpaced(text("استخدمت التلميحات الثلاثة لهذه الجولة.", 13f, color = UiColors.MUTED), 10f)
        }
    }

    fun rushSummary(score: Int, best: Int, newBest: Boolean, shells: Int, words: List<String>, newBadges: List<Badge>,
                    onAgain: () -> Unit, onClassic: () -> Unit, onBadges: () -> Unit): View = column().apply {
        addSpaced(text(ar(score), 64f, bold = true, gravity = Gravity.CENTER).apply { typeface = fonts.heavy }, 0f)
        addSpaced(text(if (score == 1) "كلمة واحدة" else if (score == 2) "كلمتان" else "كلمات حللتها", 16f, color = UiColors.MUTED, gravity = Gravity.CENTER), 0f)
        addSpaced(text(if (newBest) "🎉 رقم قياسي جديد!" else "أفضل نتيجة لك: ${ar(best)}", 17f, bold = true,
            color = if (newBest) opaque(Palette.PRESENT) else UiColors.TEXT, gravity = Gravity.CENTER), 8f)
        if (shells > 0) addSpaced(text("🐚 +${ar(shells)} صدفة", 14f, bold = true, color = opaque(Palette.PRESENT), gravity = Gravity.CENTER), 6f)
        if (words.isNotEmpty()) addSpaced(text(words.joinToString(" · "), 15f, color = UiColors.MUTED, gravity = Gravity.CENTER), 8f)
        for (b in newBadges) addSpaced(badgeBanner(b, onBadges), 8f)
        val r = row()
        r.addView(button("سباق جديد", primary = true, onClick = onAgain), LinearLayout.LayoutParams(0, wrap, 1.3f))
        r.addView(button("اللعب الحر", primary = false, onClick = onClassic), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.dpi(10f) })
        addSpaced(r, 14f)
    }

    // -------------------------------------------------------------- report a word

    fun report(word: String, onReason: (String) -> Unit): View = column().apply {
        addSpaced(text("ما المشكلة في كلمة «$word»؟ سنستبعدها من الحلول على جهازك فورًا، ونحفظ البلاغ لمراجعة القاموس.", 15f), 4f)
        for (reason in listOf("ليست كلمة عربية صحيحة", "نادرة أو غريبة جدًا", "لفظ غير لائق", "المعنى المعروض غير دقيق")) {
            addSpaced(button(reason, primary = false) { onReason(reason) }, 10f)
        }
    }
}

/** One decimal place with Arabic-Indic digits, e.g. ٣٫٥. */
fun arDecimal(v: Float): String {
    val tenths = Math.round(v * 10)
    return ar(tenths / 10) + "٫" + ar(tenths % 10)
}
