package com.khumasi.game.core

/** Builds a spoiler-free result to share: colored squares only, never letters. */
object ShareText {
    private const val RLM = '‏'

    fun build(state: GameState, title: String = "خماسي"): String {
        val score = if (state.status == GameStatus.WON) toArabicDigits(state.guesses.size) else "✗"
        val header = "$RLM$title $score/${toArabicDigits(state.maxAttempts)}"
        // Each row starts with a right-to-left mark so the first letter's square shows on the right
        // in chat apps, matching how the board reads.
        val rows = state.evaluations.joinToString("\n") { row ->
            RLM + row.joinToString("") { square(it) }
        }
        return "$header\n$rows"
    }

    private fun square(s: LetterState): String = when (s) {
        LetterState.CORRECT -> "🟩"
        LetterState.PRESENT -> "🟨"
        else -> "⬜"
    }

    fun toArabicDigits(n: Int): String {
        val digits = "٠١٢٣٤٥٦٧٨٩"
        return n.toString().map { if (it in '0'..'9') digits[it - '0'] else it }.joinToString("")
    }
}
