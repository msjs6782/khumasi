package com.khumasi.game.core

/** Result for one letter of a guess. Ordered from weakest to strongest information. */
enum class LetterState(val rank: Int) {
    UNKNOWN(0),
    ABSENT(1),
    PRESENT(2),
    CORRECT(3);

    fun strongest(other: LetterState): LetterState = if (other.rank > rank) other else this
}

object Evaluator {

    /**
     * Colors a guess against the answer.
     *
     * Exact matches are counted first. The letters of the answer that are left over are then
     * handed out, left to right in reading order, to guess letters in other positions. A letter
     * repeated in the guess more often than it occurs in the answer is marked ABSENT for the extras.
     */
    fun evaluate(guess: String, answer: String): List<LetterState> {
        require(guess.length == answer.length) { "Length mismatch: ${guess.length} vs ${answer.length}" }
        val result = Array(guess.length) { LetterState.ABSENT }
        val remaining = HashMap<Char, Int>()
        for (i in guess.indices) {
            if (guess[i] == answer[i]) {
                result[i] = LetterState.CORRECT
            } else {
                remaining[answer[i]] = (remaining[answer[i]] ?: 0) + 1
            }
        }
        for (i in guess.indices) {
            if (result[i] == LetterState.CORRECT) continue
            val left = remaining[guess[i]] ?: 0
            if (left > 0) {
                result[i] = LetterState.PRESENT
                remaining[guess[i]] = left - 1
            }
        }
        return result.toList()
    }
}

/** Keyboard key colors. A key never moves to a weaker state (green never becomes amber or gray). */
object KeyboardStates {

    fun merge(
        current: Map<Char, LetterState>,
        guess: String,
        evaluation: List<LetterState>,
    ): Map<Char, LetterState> {
        val next = HashMap(current)
        for (i in guess.indices) {
            val ch = guess[i]
            next[ch] = (next[ch] ?: LetterState.UNKNOWN).strongest(evaluation[i])
        }
        return next
    }

    fun fromGuesses(guesses: List<String>, answer: String): Map<Char, LetterState> {
        var map: Map<Char, LetterState> = emptyMap()
        for (g in guesses) map = merge(map, g, Evaluator.evaluate(g, answer))
        return map
    }
}
