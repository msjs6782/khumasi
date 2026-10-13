package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersistenceTest {

    @Test fun gameRoundTrips() {
        val s = GameState(
            answer = "مدرسة", guesses = listOf("مكتبة", "حديقة"), input = "مدر",
            playedMs = 12345, startedAtEpochMs = 1_700_000_000_000, usedHelp = true,
        )
        val back = SaveCodec.decodeGame(SaveCodec.encodeGame(s))
        assertEquals(s, back)
    }

    @Test fun statusIsRecomputedFromGuesses() {
        val text = SaveCodec.encode(mapOf("v" to "1", "answer" to "مدرسة", "guesses" to "مكتبة,مدرسة", "status" to "PLAYING"))
        assertEquals(GameStatus.WON, SaveCodec.decodeGame(text)!!.status)
    }

    @Test fun olderSaveWithMissingFieldsStillLoads() {
        val text = "v=1\nanswer=مدرسة\nguesses=مكتبة"
        val s = SaveCodec.decodeGame(text)!!
        assertEquals(listOf("مكتبة"), s.guesses)
        assertEquals(GameState.DEFAULT_ATTEMPTS, s.maxAttempts)
        assertEquals("", s.input)
    }

    @Test fun damagedOrFutureSavesAreIgnored() {
        assertNull(SaveCodec.decodeGame("garbage"))
        assertNull(SaveCodec.decodeGame("v=99\nanswer=مدرسة"))
        assertNull(SaveCodec.decodeGame("v=1\nanswer=مدرسة\nguesses=abc"))
        assertNull(SaveCodec.decodeGame(""))
        assertNull(SaveCodec.decodeProgress("v=7"))
    }

    @Test fun progressRoundTripsAndKeepsAssistedSeparate() {
        var p = Progress()
        val won = GameState(answer = "مدرسة", guesses = listOf("مكتبة", "مدرسة"), status = GameStatus.WON)
        val lostWithHelp = GameState(
            answer = "حديقة", guesses = List(8) { "مكتبة" }, status = GameStatus.LOST, usedHelp = true,
        )
        p = p.recordRound(won).recordRound(lostWithHelp)
        assertEquals(1, p.unassisted.played)
        assertEquals(1, p.unassisted.wins)
        assertEquals(1, p.unassisted.distribution[1])
        assertEquals(1, p.assisted.played)
        assertEquals(0, p.assisted.wins)
        assertEquals(setOf("مدرسة"), p.discovered)
        val back = SaveCodec.decodeProgress(SaveCodec.encodeProgress(p.rememberAnswer("مدرسة", 10)))
        assertNotNull(back)
        assertEquals(p.rememberAnswer("مدرسة", 10), back)
    }

    @Test fun streaksAndDistribution() {
        var s = Stats()
        s = s.record(true, 3).record(true, 4).record(false, 8).record(true, 1)
        assertEquals(4, s.played)
        assertEquals(3, s.wins)
        assertEquals(1, s.currentStreak)
        assertEquals(2, s.bestStreak)
        assertEquals(75, s.winRatePercent)
        assertEquals(listOf(1, 0, 1, 1, 0, 0, 0, 0), s.distribution)
    }

    @Test fun pickerAvoidsRecentAnswersAndWarmsUpWithEasyWords() {
        val pool = listOf(
            Solution("مدرسة", "مدرسة", "مكان", 1, ""),
            Solution("مكتبة", "مكتبة", "مكان", 1, ""),
            Solution("قنطرة", "قنطرة", "مكان", 3, ""),
        )
        val fresh = Progress()
        repeat(50) { assertEquals(1, SolutionPicker.pick(pool, fresh, Random(it)).difficulty) }

        val veteran = Progress(unassisted = Stats(played = 20), recentAnswers = listOf("مدرسة", "مكتبة"))
        repeat(20) { assertEquals("قنطرة", SolutionPicker.pick(pool, veteran, Random(it)).word) }

        val allUsed = veteran.copy(recentAnswers = listOf("مدرسة", "مكتبة", "قنطرة"))
        repeat(20) { assertTrue(SolutionPicker.pick(pool, allUsed, Random(it)).word == "مدرسة") }
    }

    @Test fun rememberedAnswersAreBounded() {
        var p = Progress()
        for (w in listOf("aaaaa", "bbbbb", "ccccc", "ddddd")) p = p.rememberAnswer(w, 3)
        assertEquals(listOf("ccccc", "ddddd"), p.recentAnswers)
    }

    @Test fun islandLevels() {
        assertEquals(0, Island.levelFor(0))
        assertEquals(1, Island.levelFor(1))
        assertEquals(2, Island.levelFor(5))
        assertEquals(Island.MAX_LEVEL, Island.levelFor(1000))
        assertEquals(1, Island.winsToNext(5))
        assertEquals(0, Island.winsToNext(1000))
    }

    @Test fun shareTextNeverRevealsLetters() {
        val s = GameState(answer = "مدرسة", guesses = listOf("مكتبة", "مدرسة"), status = GameStatus.WON)
        val text = ShareText.build(s)
        assertTrue(text.none { ArabicNormalizer.isGameLetter(it) && it !in "خماسي" })
        assertTrue(text.contains("٢/٨"))
        assertEquals(3, text.lines().size)
        assertTrue(text.lines().drop(1).all { it.startsWith("‏") })
    }
}
