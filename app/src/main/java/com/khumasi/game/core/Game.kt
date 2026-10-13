package com.khumasi.game.core

enum class GameStatus { PLAYING, WON, LOST }

enum class RejectReason { TOO_SHORT, NOT_IN_LIST, ALREADY_TRIED, HARD_MODE, GAME_OVER }

/**
 * One round. Immutable; every action returns a new state. Evaluations are derived from the
 * guesses and the answer so a saved round can never disagree with the coloring rules.
 */
data class GameState(
    val answer: String,
    val guesses: List<String> = emptyList(),
    val input: String = "",
    val status: GameStatus = GameStatus.PLAYING,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val mode: String = MODE_CLASSIC,
    /** Daily: the day number. Friend: the challenge code. Empty in free play. */
    val ref: String = "",
    val usedHelp: Boolean = false,
    /** Hard mode: every guess must keep the greens in place and use every revealed letter. */
    val hardMode: Boolean = false,
    /** Hints revealed this round (0-3). Any hint marks the round as assisted. */
    val hintsUsed: Int = 0,
    /** Positions revealed with the 🔦 power-up. */
    val revealed: Set<Int> = emptySet(),
    /** Letters removed from the keyboard with the 🧹 power-up (all absent from the answer). */
    val eliminated: Set<Char> = emptySet(),
    /** Active play time, counted only while the game screen is visible. */
    val playedMs: Long = 0L,
    val startedAtEpochMs: Long = 0L,
) {
    val length: Int get() = answer.length
    val currentRow: Int get() = guesses.size
    val isOver: Boolean get() = status != GameStatus.PLAYING

    val evaluations: List<List<LetterState>> by lazy(LazyThreadSafetyMode.NONE) {
        guesses.map { Evaluator.evaluate(it, answer) }
    }

    val keyboard: Map<Char, LetterState> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap(KeyboardStates.fromGuesses(guesses, answer))
        for (ch in eliminated) if ((map[ch] ?: LetterState.UNKNOWN) == LetterState.UNKNOWN) map[ch] = LetterState.ABSENT
        for (i in revealed) if (i in answer.indices) map[answer[i]] = LetterState.CORRECT
        map
    }

    /** What the player knows of each position: from green tiles or a revealed letter, else null. */
    val knownLetters: List<Char?> get() = answer.indices.map { i ->
        if (i in revealed || guesses.any { it[i] == answer[i] }) answer[i] else null
    }

    fun type(letter: Char): GameState {
        if (isOver || input.length >= length || !ArabicNormalizer.isGameLetter(letter)) return this
        return copy(input = input + letter)
    }

    fun deleteLetter(): GameState {
        if (isOver || input.isEmpty()) return this
        return copy(input = input.dropLast(1))
    }

    fun submit(isAccepted: (String) -> Boolean): SubmitResult {
        if (isOver) return SubmitResult.Rejected(RejectReason.GAME_OVER, this)
        val word = input
        if (word.length < length) return SubmitResult.Rejected(RejectReason.TOO_SHORT, this)
        if (word in guesses) return SubmitResult.Rejected(RejectReason.ALREADY_TRIED, this)
        if (word != answer && !isAccepted(word)) return SubmitResult.Rejected(RejectReason.NOT_IN_LIST, this)
        if (hardMode) {
            HardMode.violation(word, guesses, answer)?.let { return SubmitResult.Rejected(RejectReason.HARD_MODE, this, it) }
        }

        val newGuesses = guesses + word
        val status = when {
            word == answer -> GameStatus.WON
            newGuesses.size >= maxAttempts -> GameStatus.LOST
            else -> GameStatus.PLAYING
        }
        val next = copy(guesses = newGuesses, input = "", status = status)
        return SubmitResult.Accepted(next, Evaluator.evaluate(word, answer))
    }

    fun withHint(): GameState = if (isOver) this else copy(usedHelp = true, hintsUsed = hintsUsed + 1)

    fun withReveal(position: Int): GameState =
        if (isOver || position !in answer.indices) this else copy(usedHelp = true, revealed = revealed + position)

    fun withEliminated(letters: Set<Char>): GameState =
        if (isOver || letters.isEmpty()) this else copy(usedHelp = true, eliminated = eliminated + letters.filter { it !in answer })

    fun addPlayTime(ms: Long): GameState = if (isOver || ms <= 0) this else copy(playedMs = playedMs + ms)

    companion object {
        const val DEFAULT_ATTEMPTS = 8
        const val MODE_CLASSIC = "classic"
        const val MODE_DAILY = "daily"
        const val MODE_FRIEND = "friend"
        const val MODE_RUSH = "rush"
    }
}

sealed interface SubmitResult {
    data class Accepted(val state: GameState, val evaluation: List<LetterState>) : SubmitResult
    data class Rejected(val reason: RejectReason, val state: GameState, val detail: String? = null) : SubmitResult
}

/** Hard-mode rule check, with a message naming the first rule the guess breaks. */
object HardMode {
    private val ORDINALS = listOf("الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع")

    fun violation(word: String, guesses: List<String>, answer: String): String? {
        for (g in guesses) {
            val e = Evaluator.evaluate(g, answer)
            for (i in g.indices) {
                if (e[i] == LetterState.CORRECT && word[i] != g[i]) {
                    return "الحرف ${ORDINALS.getOrElse(i) { (i + 1).toString() }} يجب أن يكون «${g[i]}»"
                }
            }
            val need = LinkedHashMap<Char, Int>()
            for (i in g.indices) if (e[i] != LetterState.ABSENT) need[g[i]] = (need[g[i]] ?: 0) + 1
            for ((ch, n) in need) {
                if (word.count { it == ch } < n) {
                    return if (n == 1) "يجب أن تحتوي الكلمة على «$ch»" else "يجب أن تحتوي الكلمة على «$ch» مرتين على الأقل"
                }
            }
        }
        return null
    }
}
