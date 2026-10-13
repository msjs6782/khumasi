package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4Test {

    private val pool = listOf("مدرسة", "مكتبة", "سيارة", "حديقة", "جزيرة", "مدينة", "عاصمة", "جامعة", "طائرة", "قنطرة")
        .map { Solution(it, it, "عام", 1, "") }

    private fun play(s: GameState, vararg words: String) = words.fold(s) { st, w ->
        val typed = w.fold(st) { acc, c -> acc.type(c) }
        (typed.submit { true } as SubmitResult.Accepted).state
    }

    @Test fun dailyDayNumbers() {
        val day1Noon = Daily.FIRST_EPOCH_DAY * 86_400_000L + 12 * 3_600_000L
        assertEquals(1, Daily.number(Daily.localEpochDay(day1Noon, 0)))
        // 23:30 UTC on day 1 is already day 2 in Riyadh (UTC+3).
        val late = Daily.FIRST_EPOCH_DAY * 86_400_000L + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(1, Daily.number(Daily.localEpochDay(late, 0)))
        assertEquals(2, Daily.number(Daily.localEpochDay(late, 3 * 3_600_000)))
        assertEquals(30 * 60_000L, Daily.msUntilNext(late, 0))
        assertEquals(1, Daily.number(Daily.FIRST_EPOCH_DAY - 5)) // a phone set to an earlier date
        assertEquals(13, Daily.number(20739L)) // 13 October 2026
    }

    @Test fun dailyWordIsStableAndCycles() {
        val a = Daily.word(5, pool).word
        assertEquals(a, Daily.word(5, pool.shuffled()).word) // file order does not matter
        val first = (1..pool.size).map { Daily.word(it, pool).word }
        assertEquals(pool.size, first.toSet().size) // every word once per cycle
        assertEquals(Daily.word(1, pool).word, Daily.word(1 + pool.size, pool).word)
    }

    @Test fun dailyStreakNeedsConsecutiveDays() {
        var p = Progress()
        fun day(n: Int, win: Boolean) {
            val s = GameState(answer = "مدرسة", mode = GameState.MODE_DAILY, ref = n.toString())
            p = p.recordRound(if (win) play(s, "مدرسة") else play(s, "مكتبة", "سيارة", "حديقة", "جزيرة", "مدينة", "عاصمة", "جامعة", "طائرة"))
        }
        day(3, true); day(4, true)
        assertEquals(2, p.daily.currentStreak)
        assertEquals(2, p.dailyStreak(5))
        assertEquals(0, p.dailyStreak(6)) // a whole day skipped
        day(4, true) // the same day again is ignored
        assertEquals(2, p.daily.played)
        day(6, true) // after a gap the streak restarts
        assertEquals(1, p.daily.currentStreak)
        assertEquals(2, p.daily.bestStreak)
        assertTrue(p.dailyDone(6))
        assertFalse(Progress().dailyDone(0)) // "never played" is not "done today"
        assertEquals(0, p.unassisted.played) // free-play statistics are untouched
        assertEquals(3, p.totalWins)
    }

    @Test fun progressKeepsDailyAfterSave() {
        val p = Progress(daily = Stats(played = 4, wins = 3, currentStreak = 2, bestStreak = 3), lastDailyDay = 9, friendPlayed = 2, friendWins = 1)
        val back = SaveCodec.decodeProgress(SaveCodec.encodeProgress(p))!!
        assertEquals(p.daily, back.daily)
        assertEquals(9, back.lastDailyDay)
        assertEquals(1, back.friendWins)
        // A save from before Phase 4 still loads, with empty daily results.
        val old = SaveCodec.decodeProgress("v=1\nu=3;2;1;2|0;1;1;0;0;0;0;0")!!
        assertEquals(0, old.daily.played)
        val g = GameState(answer = "مدرسة", mode = GameState.MODE_DAILY, ref = "12")
        assertEquals("12", SaveCodec.decodeGame(SaveCodec.encodeGame(g))!!.ref)
    }

    @Test fun friendCodeRoundTrip() {
        for (w in listOf("مدرسة", "سلسلة", "مؤتمر", "ءءءءء", "يييية")) {
            for (salt in listOf(0, 7, 255)) {
                val code = FriendCode.encode(w, salt)
                assertTrue(code, code.matches(Regex("KH-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{5}")))
                assertEquals(w, FriendCode.decode(code))
                assertEquals(w, FriendCode.decode("تحدّيتك في خماسي! الرمز: ${code.lowercase()} افتح اللعبة"))
            }
        }
        // The same word looks different with another salt, and the code does not spell it.
        assertNotEquals(FriendCode.encode("مدرسة", 1), FriendCode.encode("مدرسة", 2))
    }

    @Test fun friendCodeRejectsTypos() {
        val code = FriendCode.encode("مدرسة", 42)
        val last = code.last()
        val typo = code.dropLast(1) + (if (last == 'A') 'B' else 'A')
        val wrong = FriendCode.decode(typo)
        assertTrue(wrong == null || wrong == "مدرسة")
        val swapped = code.substring(0, 3) + code[4] + code[3] + code.substring(5)
        assertNull(FriendCode.decode(swapped))
        assertNull(FriendCode.decode("لا يوجد رمز هنا"))
        assertNull(FriendCode.decode("KH-AAAA-AAAA-AAAAA"))
    }

    @Test fun backupRoundTrip() {
        val values = mapOf(
            "game" to "v=1\nanswer=مدرسة",
            "sound" to false,
            "text_size" to 2,
            "since" to 123456789012L,
            "reported_words" to setOf("سيارة", "مكتبة"),
        )
        val text = Backup.encode(values, 99L)
        val back = Backup.decode(text)
        assertNotNull(back)
        assertEquals(values, back!!.values)
        assertEquals(99L, back.createdMs)
        assertNull(Backup.decode("hello=1"))
        assertNull(Backup.decode("khumasi-backup=9"))
        assertFalse(Backup.decode("khumasi-backup=1")!!.values.isNotEmpty())
    }

    @Test fun shareTitleShowsMode() {
        val s = play(GameState(answer = "مدرسة", mode = GameState.MODE_DAILY, ref = "12"), "مدرسة")
        assertTrue(ShareText.build(s).contains("خماسي اليوم ١٢ ١/٨"))
        val hinted = play(GameState(answer = "مدرسة").withHint(), "مدرسة")
        assertTrue(ShareText.build(hinted).startsWith("‏خماسي ١/٨ 💡"))
    }
}
