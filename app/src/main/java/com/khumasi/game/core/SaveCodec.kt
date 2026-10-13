package com.khumasi.game.core

/**
 * Text format for saved data: one `key=value` per line, with a schema version. New fields get
 * defaults when missing, so older saves keep loading after an update. Values are escaped so any
 * text is safe. A save that cannot be read returns null instead of crashing the app.
 */
object SaveCodec {
    const val GAME_VERSION = 1
    const val PROGRESS_VERSION = 1

    fun encodeGame(s: GameState): String = encode(
        linkedMapOf(
            "v" to GAME_VERSION.toString(),
            "answer" to s.answer,
            "guesses" to s.guesses.joinToString(","),
            "input" to s.input,
            "status" to s.status.name,
            "max" to s.maxAttempts.toString(),
            "mode" to s.mode,
            "ref" to s.ref,
            "help" to if (s.usedHelp) "1" else "0",
            "hard" to if (s.hardMode) "1" else "0",
            "hints" to s.hintsUsed.toString(),
            "playedMs" to s.playedMs.toString(),
            "startedAt" to s.startedAtEpochMs.toString(),
        )
    )

    fun decodeGame(text: String?): GameState? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val m = decode(text)
            val version = m["v"]?.toIntOrNull() ?: return null
            if (version > GAME_VERSION) return null
            val answer = m["answer"].orEmpty()
            if (!ArabicNormalizer.isClean(answer)) return null
            val guesses = m["guesses"].orEmpty().split(',').filter { it.isNotEmpty() }
            if (guesses.any { it.length != answer.length || !ArabicNormalizer.isClean(it) }) return null
            val max = m["max"]?.toIntOrNull()?.takeIf { it in 1..20 } ?: GameState.DEFAULT_ATTEMPTS
            val input = m["input"].orEmpty().filter { ArabicNormalizer.isGameLetter(it) }.take(answer.length)
            // Status is recomputed from the guesses so a damaged save can't claim a false result.
            val status = when {
                guesses.lastOrNull() == answer -> GameStatus.WON
                guesses.size >= max -> GameStatus.LOST
                else -> GameStatus.PLAYING
            }
            GameState(
                answer = answer,
                guesses = guesses,
                input = if (status == GameStatus.PLAYING) input else "",
                status = status,
                maxAttempts = max,
                mode = m["mode"] ?: GameState.MODE_CLASSIC,
                ref = m["ref"].orEmpty(),
                usedHelp = m["help"] == "1",
                hardMode = m["hard"] == "1",
                hintsUsed = m["hints"]?.toIntOrNull()?.coerceIn(0, 3) ?: 0,
                playedMs = m["playedMs"]?.toLongOrNull() ?: 0L,
                startedAtEpochMs = m["startedAt"]?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    fun encodeProgress(p: Progress): String = encode(
        linkedMapOf(
            "v" to PROGRESS_VERSION.toString(),
            "u" to encodeStats(p.unassisted),
            "a" to encodeStats(p.assisted),
            "discovered" to p.discovered.sorted().joinToString(","),
            "recent" to p.recentAnswers.joinToString(","),
            "bestMs" to p.bestWinMs.toString(),
            "d" to encodeStats(p.daily),
            "lastDaily" to p.lastDailyDay.toString(),
            "friend" to "${p.friendPlayed};${p.friendWins}",
        )
    )

    fun decodeProgress(text: String?): Progress? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val m = decode(text)
            val version = m["v"]?.toIntOrNull() ?: return null
            if (version > PROGRESS_VERSION) return null
            Progress(
                unassisted = decodeStats(m["u"]),
                assisted = decodeStats(m["a"]),
                discovered = m["discovered"].orEmpty().split(',').filter { ArabicNormalizer.isClean(it) }.toSet(),
                recentAnswers = m["recent"].orEmpty().split(',').filter { ArabicNormalizer.isClean(it) },
                bestWinMs = m["bestMs"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L,
                daily = decodeStats(m["d"]),
                lastDailyDay = m["lastDaily"]?.toIntOrNull() ?: 0,
                friendPlayed = m["friend"]?.substringBefore(';')?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                friendWins = m["friend"]?.substringAfter(';', "")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }.getOrNull()
    }

    private fun encodeStats(s: Stats): String =
        listOf(s.played, s.wins, s.currentStreak, s.bestStreak).joinToString(";") +
            "|" + s.distribution.joinToString(";")

    private fun decodeStats(text: String?): Stats {
        if (text.isNullOrBlank()) return Stats()
        val (head, dist) = text.split('|').let { it[0] to it.getOrElse(1) { "" } }
        val n = head.split(';').map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
        val d = dist.split(';').mapNotNull { it.toIntOrNull()?.coerceAtLeast(0) }.toMutableList()
        while (d.size < GameState.DEFAULT_ATTEMPTS) d.add(0)
        return Stats(
            played = n.getOrElse(0) { 0 },
            wins = n.getOrElse(1) { 0 },
            currentStreak = n.getOrElse(2) { 0 },
            bestStreak = n.getOrElse(3) { 0 },
            distribution = d,
        )
    }

    fun encode(map: Map<String, String>): String =
        map.entries.joinToString("\n") { (k, v) -> "$k=${escape(v)}" }

    fun decode(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out[line.substring(0, eq)] = unescape(line.substring(eq + 1))
        }
        return out
    }

    private fun escape(v: String): String = buildString {
        for (c in v) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(v: String): String = buildString {
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(v[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
