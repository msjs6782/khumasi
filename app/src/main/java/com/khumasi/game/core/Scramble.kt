package com.khumasi.game.core

import kotlin.random.Random

/** One «الكلمة المبعثرة» puzzle; [alternates] are other common words with the same letters. */
data class ScramblePuzzle(val word: String, val display: String, val category: String, val alternates: Set<String>) {
    fun accepts(attempt: String): Boolean = attempt == word || attempt in alternates
}

object ScrambleList {
    fun parse(lines: Sequence<String>): List<ScramblePuzzle> = lines
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) return@mapNotNull null
            val word = p[0].trim()
            if (!ArabicNormalizer.isClean(word) || word.length !in 3..7) return@mapNotNull null
            val alts = p.getOrNull(3).orEmpty().split(',').map { it.trim() }
                .filter { it.length == word.length && ArabicNormalizer.isClean(it) }.toSet()
            ScramblePuzzle(word, p[1].trim().ifEmpty { word }, p[2].trim(), alts)
        }
        .toList()
}

/**
 * Stages get longer as you go: 3 letters first, then 4, 5, 6 and 7, then a mix. Each length has
 * its own fixed shuffled order, so a stage is always the same puzzle with the same scramble.
 */
object ScrambleLevels {
    private const val SEED = 0x5C2A3BL
    private val LATER = intArrayOf(4, 5, 6, 5, 7, 5, 6, 3)

    fun lengthFor(stage: Int): Int = when {
        stage <= 5 -> 3
        stage <= 15 -> 4
        stage <= 35 -> 5
        stage <= 55 -> 6
        stage <= 65 -> 7
        else -> LATER[(stage - 66) % LATER.size]
    }

    fun puzzleFor(stage: Int, puzzles: List<ScramblePuzzle>): ScramblePuzzle {
        require(puzzles.isNotEmpty()) { "No puzzles" }
        val byLength = puzzles.groupBy { it.word.length }
        var length = lengthFor(stage)
        if (byLength[length].isNullOrEmpty()) length = byLength.keys.minByOrNull { kotlin.math.abs(it - length) }!!
        // How many earlier stages used this length decides which word comes next.
        var index = 0
        for (s in 1 until stage) if (lengthFor(s) == length) index++
        val pool = byLength.getValue(length).sortedBy { it.word }
        val order = pool.indices.toMutableList()
        val r = Random(SEED + length)
        for (i in order.lastIndex downTo 1) { val j = r.nextInt(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
        return pool[order[index % pool.size]]
    }

    /** Letters in a fixed mixed order that is not already the word or one of its alternates. */
    fun scrambled(puzzle: ScramblePuzzle, stage: Int, attempt: Int = 0): String {
        val letters = puzzle.word.toList()
        val r = Random(stage * 7919L + attempt)
        repeat(50) {
            val s = letters.shuffled(r).joinToString("")
            if (!puzzle.accepts(s)) return s
        }
        return puzzle.word.reversed()
    }

    /** ★★★ with no help and no wrong tries; one of either costs a star; more leaves one star. */
    fun stars(hints: Int, mistakes: Int): Int = when {
        hints == 0 && mistakes == 0 -> 3
        hints + mistakes <= 1 -> 2
        else -> 1
    }

    fun shells(stars: Int): Int = if (stars >= 3) 2 else 1

    const val HINT_COST = 2
    const val SKIP_COST = 5
}
