package com.khumasi.game.core

import kotlin.random.Random

/** 🐚 Shells: earned by winning, spent on power-ups. */
object Shells {
    const val REVEAL_COST = 6
    const val ELIMINATE_COST = 4
    const val MAX_REVEALS = 2
    const val ELIMINATE_COUNT = 5

    /**
     * Shells for a finished round, given the progress after it was recorded. Fewer attempts pay
     * more; help halves the reward; streak milestones add a bonus.
     */
    fun forRound(state: GameState, after: Progress): Int {
        if (state.status != GameStatus.WON) return 0
        return when (state.mode) {
            GameState.MODE_FRIEND -> 3
            GameState.MODE_RUSH -> 1
            else -> {
                var n = 2 + maxOf(0, 6 - state.guesses.size)
                if (state.usedHelp) n = maxOf(1, n / 2)
                if (state.mode == GameState.MODE_DAILY) {
                    n += 3
                    if (after.daily.currentStreak > 0 && after.daily.currentStreak % 7 == 0) n += 10
                } else if (!state.usedHelp && after.unassisted.currentStreak > 0 && after.unassisted.currentStreak % 5 == 0) {
                    n += 5
                }
                n
            }
        }
    }

    /** For a race: one per word during the race, plus half the score as a bonus at the end. */
    fun forRushEnd(score: Int): Int = score / 2
}

/** 🔦 reveal a letter and 🧹 remove wrong letters. Both mark the round as assisted. */
object PowerUps {
    /** A position the player does not know yet, or null when every letter is known. */
    fun revealPosition(state: GameState, random: Random = Random.Default): Int? {
        val unknown = state.knownLetters.withIndex().filter { it.value == null }.map { it.index }
        return if (unknown.isEmpty()) null else unknown[random.nextInt(unknown.size)]
    }

    /** Letters not in the answer that the keyboard does not show as gray yet. */
    fun lettersToEliminate(state: GameState, count: Int = Shells.ELIMINATE_COUNT, random: Random = Random.Default): Set<Char> {
        val keys = state.keyboard
        val candidates = ArabicNormalizer.LETTERS.filter { it !in state.answer && (keys[it] ?: LetterState.UNKNOWN) == LetterState.UNKNOWN }
        return candidates.toList().shuffled(random).take(count).toSet()
    }
}

/** ⏱️ Race against the clock: solve as many words as possible; each solved word adds time. */
object Rush {
    const val START_MS = 180_000L
    const val BONUS_MS = 20_000L
    const val MAX_MS = 300_000L

    /** Session state kept in the round's ref: "score;remainingMs;solved words". */
    data class Session(val score: Int = 0, val remainingMs: Long = START_MS, val solved: List<String> = emptyList()) {
        fun encode(): String = "$score;$remainingMs;${solved.joinToString(",")}"
        fun tick(ms: Long): Session = copy(remainingMs = maxOf(0L, remainingMs - ms))
        fun solvedWord(word: String): Session =
            copy(score = score + 1, remainingMs = minOf(MAX_MS, remainingMs + BONUS_MS), solved = solved + word)
        val isOver: Boolean get() = remainingMs <= 0
    }

    fun decode(ref: String): Session {
        val p = ref.split(';')
        return Session(
            score = p.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            remainingMs = p.getOrNull(1)?.toLongOrNull()?.coerceIn(0L, MAX_MS) ?: START_MS,
            solved = p.getOrNull(2).orEmpty().split(',').filter { ArabicNormalizer.isClean(it) && it.isNotEmpty() },
        )
    }
}

data class Badge(val id: String, val emoji: String, val title: String, val description: String, val shells: Int = 5)

/** Badges unlocked by playing. Conditions use the progress after a round and that round. */
object Achievements {
    val ALL = listOf(
        Badge("first_win", "🌱", "البداية", "أول فوز لك"),
        Badge("genius", "🧠", "عبقري", "فوز من المحاولة الأولى", 15),
        Badge("two", "🎯", "قارئ الأفكار", "فوز في محاولتين"),
        Badge("close_call", "😅", "في اللحظة الأخيرة", "فوز في المحاولة الثامنة"),
        Badge("lightning", "⚡", "سريع البرق", "فوز دون مساعدة في أقل من دقيقة"),
        Badge("hard_win", "🛡️", "صلب", "فوز في الوضع الصعب دون مساعدة"),
        Badge("streak5", "🔥", "متّقد", "سلسلة من ٥ انتصارات"),
        Badge("streak10", "☄️", "لا يُوقَف", "سلسلة من ١٠ انتصارات", 10),
        Badge("week", "📅", "أسبوع كامل", "الفوز بتحدي اليوم ٧ أيام متتالية", 15),
        Badge("friend", "🤝", "وفيّ", "الفوز بتحدي صديق"),
        Badge("rush5", "⏱️", "سبّاق", "حل ٥ كلمات في سباق واحد"),
        Badge("rush10", "🏎️", "صاروخ", "حل ١٠ كلمات في سباق واحد", 15),
        Badge("scramble10", "🔀", "مرتّب", "حل ١٠ مراحل في الكلمة المبعثرة"),
        Badge("scramble50", "🧩", "فكّاك الحروف", "حل ٥٠ مرحلة في الكلمة المبعثرة", 15),
        Badge("wins50", "🏆", "بطل", "٥٠ فوزًا", 10),
        Badge("words100", "📚", "قاموس متنقّل", "اكتشاف ١٠٠ كلمة", 10),
        Badge("shells100", "🐚", "جامع الأصداف", "جمع ١٠٠ صدفة"),
        Badge("island_max", "🏝️", "سيد الجزيرة", "إكمال الجزيرة", 20),
    )

    private val byId = ALL.associateBy { it.id }
    fun get(id: String): Badge? = byId[id]

    /** Badges whose condition now holds and that the player does not have yet, in list order. */
    fun newlyEarned(p: Progress, last: GameState?): List<Badge> {
        val won = last?.status == GameStatus.WON
        val classicLike = last?.mode != GameState.MODE_RUSH
        val holds = mapOf(
            "first_win" to (p.totalWins >= 1),
            "genius" to (won && classicLike && last!!.guesses.size == 1),
            "two" to (won && classicLike && last!!.guesses.size == 2),
            "close_call" to (won && last!!.guesses.size == 8),
            "lightning" to (won && classicLike && !last!!.usedHelp && last.playedMs in 1..59_999),
            "hard_win" to (won && last!!.hardMode && !last.usedHelp),
            "streak5" to (p.unassisted.bestStreak >= 5),
            "streak10" to (p.unassisted.bestStreak >= 10),
            "week" to (p.daily.bestStreak >= 7),
            "friend" to (p.friendWins >= 1),
            "rush5" to (p.bestRush >= 5),
            "rush10" to (p.bestRush >= 10),
            "scramble10" to (p.scrambleSolved >= 10),
            "scramble50" to (p.scrambleSolved >= 50),
            "wins50" to (p.totalWins >= 50),
            "words100" to (p.discovered.size >= 100),
            "shells100" to (p.shellsEarned >= 100),
            "island_max" to (p.islandLevel >= Island.MAX_LEVEL),
        )
        return ALL.filter { holds[it.id] == true && it.id !in p.badges }
    }

    /** Adds the badges and their shell rewards. */
    fun grant(p: Progress, badges: List<Badge>): Progress =
        if (badges.isEmpty()) p else p.copy(badges = p.badges + badges.map { it.id }).earn(badges.sumOf { it.shells })
}
