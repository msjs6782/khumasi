package com.khumasi.game.core

import kotlin.random.Random

/** Results for one kind of play. Rounds with help (hints, later phases) are kept separately. */
data class Stats(
    val played: Int = 0,
    val wins: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    /** distribution[i] = wins on attempt i+1. */
    val distribution: List<Int> = List(GameState.DEFAULT_ATTEMPTS) { 0 },
) {
    val winRatePercent: Int get() = if (played == 0) 0 else Math.round(wins * 100f / played)

    /** Average attempts over won rounds, or 0 when there are none. */
    val averageAttempts: Float get() {
        val w = distribution.sum()
        if (w == 0) return 0f
        return distribution.withIndex().sumOf { (i, n) -> (i + 1) * n }.toFloat() / w
    }

    fun record(won: Boolean, attempts: Int): Stats {
        val dist = distribution.toMutableList()
        while (dist.size < GameState.DEFAULT_ATTEMPTS) dist.add(0)
        if (won && attempts in 1..dist.size) dist[attempts - 1]++
        val streak = if (won) currentStreak + 1 else 0
        return Stats(
            played = played + 1,
            wins = wins + if (won) 1 else 0,
            currentStreak = streak,
            bestStreak = maxOf(bestStreak, streak),
            distribution = dist,
        )
    }
}

/** Everything that survives across rounds. */
data class Progress(
    val unassisted: Stats = Stats(),
    val assisted: Stats = Stats(),
    /** Distinct words the player has solved. */
    val discovered: Set<String> = emptySet(),
    /** Answers already used, oldest first, to avoid repeating solutions. */
    val recentAnswers: List<String> = emptyList(),
    /** Fastest unassisted win (active play time), 0 when none yet. */
    val bestWinMs: Long = 0L,
    /** Daily challenge results; the streak counts consecutive days won. */
    val daily: Stats = Stats(),
    /** Number of the last daily challenge finished (0 = never). */
    val lastDailyDay: Int = 0,
    val friendPlayed: Int = 0,
    val friendWins: Int = 0,
) {
    val totalWins: Int get() = unassisted.wins + assisted.wins + daily.wins + friendWins
    val islandLevel: Int get() = Island.levelFor(totalWins)

    fun dailyDone(day: Int): Boolean = day > 0 && lastDailyDay == day

    /** The daily streak as it stands today: it is lost once a whole day passes without a win. */
    fun dailyStreak(today: Int): Int = if (lastDailyDay >= today - 1) daily.currentStreak else 0

    fun recordRound(state: GameState): Progress {
        val won = state.status == GameStatus.WON
        val discoveredNow = if (won) discovered + state.answer else discovered
        when (state.mode) {
            GameState.MODE_DAILY -> {
                val day = state.ref.toIntOrNull() ?: return this
                if (day <= lastDailyDay) return this // already counted
                val base = if (lastDailyDay == day - 1) daily else daily.copy(currentStreak = 0)
                return copy(daily = base.record(won, state.guesses.size), lastDailyDay = day, discovered = discoveredNow)
            }
            GameState.MODE_FRIEND -> return copy(
                friendPlayed = friendPlayed + 1,
                friendWins = friendWins + if (won) 1 else 0,
                discovered = discoveredNow,
            )
        }
        return copy(
            unassisted = if (state.usedHelp) unassisted else unassisted.record(won, state.guesses.size),
            assisted = if (state.usedHelp) assisted.record(won, state.guesses.size) else assisted,
            discovered = discoveredNow,
            bestWinMs = if (won && !state.usedHelp && state.playedMs > 0 && (bestWinMs == 0L || state.playedMs < bestWinMs)) state.playedMs else bestWinMs,
        )
    }

    fun rememberAnswer(word: String, poolSize: Int): Progress {
        val keep = (poolSize - 1).coerceAtLeast(0)
        val list = (recentAnswers - word) + word
        return copy(recentAnswers = if (list.size > keep) list.takeLast(keep) else list)
    }
}

object SolutionPicker {
    /** Games played before medium and hard words join the pool. */
    const val WARMUP_GAMES = 5

    /**
     * Picks a word that has not been used recently. New players get only easy words for their
     * first few rounds. When every word has been used, the oldest ones become available again.
     */
    fun pick(solutions: List<Solution>, progress: Progress, random: Random = Random.Default): Solution {
        require(solutions.isNotEmpty()) { "No solutions" }
        val played = progress.unassisted.played + progress.assisted.played
        val pool = if (played < WARMUP_GAMES) {
            solutions.filter { it.difficulty == 1 }.ifEmpty { solutions }
        } else {
            solutions
        }
        val recent = progress.recentAnswers.toHashSet()
        val fresh = pool.filter { it.word !in recent }
        if (fresh.isNotEmpty()) return fresh[random.nextInt(fresh.size)]
        // Everything was used: take from the older half of the history.
        val oldest = progress.recentAnswers.take(maxOf(1, progress.recentAnswers.size / 2)).toHashSet()
        val again = pool.filter { it.word in oldest }.ifEmpty { pool }
        return again[random.nextInt(again.size)]
    }
}

/** The island behind the board grows with total wins. */
object Island {
    /** Wins needed to reach level i (index). */
    val THRESHOLDS = intArrayOf(0, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55)
    val MAX_LEVEL: Int get() = THRESHOLDS.size - 1

    fun levelFor(wins: Int): Int {
        var level = 0
        for (i in THRESHOLDS.indices) if (wins >= THRESHOLDS[i]) level = i
        return level
    }

    /** Wins still needed for the next level, or 0 at the top level. */
    fun winsToNext(wins: Int): Int {
        val level = levelFor(wins)
        return if (level >= MAX_LEVEL) 0 else THRESHOLDS[level + 1] - wins
    }
}
