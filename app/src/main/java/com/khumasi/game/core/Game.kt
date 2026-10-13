package com.khumasi.game.core

enum class GameStatus { PLAYING, WON, LOST }

enum class RejectReason { TOO_SHORT, NOT_IN_LIST, ALREADY_TRIED, GAME_OVER }

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
    val usedHelp: Boolean = false,
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
        KeyboardStates.fromGuesses(guesses, answer)
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

        val newGuesses = guesses + word
        val status = when {
            word == answer -> GameStatus.WON
            newGuesses.size >= maxAttempts -> GameStatus.LOST
            else -> GameStatus.PLAYING
        }
        val next = copy(guesses = newGuesses, input = "", status = status)
        return SubmitResult.Accepted(next, Evaluator.evaluate(word, answer))
    }

    fun addPlayTime(ms: Long): GameState = if (isOver || ms <= 0) this else copy(playedMs = playedMs + ms)

    companion object {
        const val DEFAULT_ATTEMPTS = 8
        const val MODE_CLASSIC = "classic"
    }
}

sealed interface SubmitResult {
    data class Accepted(val state: GameState, val evaluation: List<LetterState>) : SubmitResult
    data class Rejected(val reason: RejectReason, val state: GameState) : SubmitResult
}
