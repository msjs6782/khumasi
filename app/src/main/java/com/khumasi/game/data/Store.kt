package com.khumasi.game.data

import android.content.Context
import com.khumasi.game.core.ArabicNormalizer
import com.khumasi.game.core.Backup
import com.khumasi.game.core.GameState
import com.khumasi.game.core.GuessList
import com.khumasi.game.core.Progress
import com.khumasi.game.core.SaveCodec
import com.khumasi.game.core.Solution
import com.khumasi.game.core.Solutions
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Local persistence. Keys are versioned and never renamed, so an update installed over an older
 * version keeps the current round, statistics and settings.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("khumasi", Context.MODE_PRIVATE)

    /** Each mode keeps its own round, so a daily challenge never replaces an unfinished free round. */
    private fun gameKey(mode: String) = when (mode) {
        GameState.MODE_DAILY -> "game_daily"
        GameState.MODE_FRIEND -> "game_friend"
        else -> KEY_GAME
    }

    fun loadGame(mode: String = GameState.MODE_CLASSIC): GameState? =
        SaveCodec.decodeGame(prefs.getString(gameKey(mode), null))?.takeIf { it.mode == mode }

    fun saveGame(state: GameState) = prefs.edit()
        .putString(gameKey(state.mode), SaveCodec.encodeGame(state))
        .putString(KEY_ACTIVE, state.mode)
        .apply()

    /** Saves the round and the statistics together so they can never get out of step. */
    fun saveRound(state: GameState, progress: Progress) = prefs.edit()
        .putString(gameKey(state.mode), SaveCodec.encodeGame(state))
        .putString(KEY_ACTIVE, state.mode)
        .putString(KEY_PROGRESS, SaveCodec.encodeProgress(progress))
        .apply()

    /** The mode that was on screen when the app was last closed. */
    val activeMode: String get() = prefs.getString(KEY_ACTIVE, GameState.MODE_CLASSIC) ?: GameState.MODE_CLASSIC

    // ---------------------------------------------------------------- backup file

    /** Everything in the game's saved data (not the Gemini key or its cache, kept elsewhere). */
    fun exportBackup(): String = Backup.encode(prefs.all, System.currentTimeMillis())

    /** Replaces the saved data with a backup; false (and nothing changed) if it is not valid. */
    fun importBackup(text: String): Boolean {
        val b = Backup.decode(text) ?: return false
        if (b.values[KEY_PROGRESS] !is String) return false
        val e = prefs.edit().clear()
        for ((k, v) in b.values) {
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v as Set<String>)
            }
        }
        return e.commit()
    }

    fun loadProgress(): Progress = SaveCodec.decodeProgress(prefs.getString(KEY_PROGRESS, null)) ?: Progress()
    fun saveProgress(p: Progress) = prefs.edit().putString(KEY_PROGRESS, SaveCodec.encodeProgress(p)).apply()

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(v) = prefs.edit().putBoolean("reduce_motion", v).apply()
    var lightMode: Boolean
        get() = prefs.getBoolean("light_mode", false)
        set(v) = prefs.edit().putBoolean("light_mode", v).apply()
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()
    var music: Boolean
        get() = prefs.getBoolean("music", false)
        set(v) = prefs.edit().putBoolean("music", v).apply()
    var colorBlind: Boolean
        get() = prefs.getBoolean("color_blind", false)
        set(v) = prefs.edit().putBoolean("color_blind", v).apply()
    /** 0 small, 1 medium, 2 large. */
    var textSize: Int
        get() = prefs.getInt("text_size", 1).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("text_size", v).apply()
    var showTimer: Boolean
        get() = prefs.getBoolean("show_timer", false)
        set(v) = prefs.edit().putBoolean("show_timer", v).apply()
    var hardMode: Boolean
        get() = prefs.getBoolean("hard_mode", false)
        set(v) = prefs.edit().putBoolean("hard_mode", v).apply()
    var tileTheme: String
        get() = prefs.getString("tile_theme", "ivory") ?: "ivory"
        set(v) = prefs.edit().putString("tile_theme", v).apply()
    var skyTheme: String
        get() = prefs.getString("sky_theme", "day") ?: "day"
        set(v) = prefs.edit().putString("sky_theme", v).apply()

    var seenRules: Boolean
        get() = prefs.getBoolean("seen_rules", false)
        set(v) = prefs.edit().putBoolean("seen_rules", v).apply()

    /** Words the player reported as wrong; they are never chosen as a solution again on this device. */
    val reportedWords: Set<String> get() = prefs.getStringSet("reported_words", emptySet()) ?: emptySet()

    fun addReport(word: String, reason: String) {
        val words = HashSet(reportedWords).apply { add(word) }
        val log = prefs.getString("report_log", "").orEmpty()
        val line = "${System.currentTimeMillis()}\t$word\t$reason"
        prefs.edit()
            .putStringSet("reported_words", words)
            .putString("report_log", (log.lines().filter { it.isNotBlank() }.takeLast(199) + line).joinToString("\n"))
            .apply()
    }

    companion object {
        private const val KEY_GAME = "game"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_ACTIVE = "active_mode"
    }
}

/** Word lists bundled in the APK (works offline). */
class WordRepository(private val context: Context) {

    fun loadSolutions(length: Int = 5): List<Solution> =
        context.assets.open("dict/solutions$length.tsv").use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { Solutions.parse(it) }
        }

    fun loadGuesses(length: Int, solutions: List<Solution>): GuessList {
        val text = context.assets.open("dict/guesses$length.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
        val list = GuessList.fromText(length, text)
        // Every solution must be accepted; the build tool guarantees it, this keeps it true regardless.
        val missing = solutions.map { it.word }.filter { ArabicNormalizer.isClean(it) && it !in list }
        return if (missing.isEmpty()) list else GuessList.fromText(length, text, missing)
    }
}
