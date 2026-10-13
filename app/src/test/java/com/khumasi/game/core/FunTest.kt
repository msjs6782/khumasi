package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FunTest {

    private fun play(s: GameState, vararg words: String) = words.fold(s) { st, w ->
        val typed = w.fold(st) { acc, c -> acc.type(c) }
        (typed.submit { true } as SubmitResult.Accepted).state
    }

    @Test fun shellsRewardFewerAttempts() {
        val p = Progress()
        assertEquals(7, Shells.forRound(play(GameState(answer = "مدرسة"), "مدرسة"), p))
        assertEquals(4, Shells.forRound(play(GameState(answer = "مدرسة"), "مكتبة", "سيارة", "حديقة", "مدرسة"), p))
        assertEquals(2, Shells.forRound(play(GameState(answer = "مدرسة"), "مكتبة", "سيارة", "حديقة", "جزيرة", "مدينة", "مدرسة"), p))
        assertEquals(0, Shells.forRound(play(GameState(answer = "مدرسة"), "مكتبة", "سيارة", "حديقة", "جزيرة", "مدينة", "عاصمة", "جامعة", "طائرة"), p))
        // Help halves it; the daily challenge adds 3.
        assertEquals(3, Shells.forRound(play(GameState(answer = "مدرسة").withReveal(0), "مدرسة"), p))
        assertEquals(10, Shells.forRound(play(GameState(answer = "مدرسة", mode = GameState.MODE_DAILY, ref = "3"), "مدرسة"), p))
    }

    @Test fun spendingNeedsEnoughShells() {
        val p = Progress().earn(5)
        assertNull(p.spend(Shells.REVEAL_COST))
        assertEquals(1, p.spend(Shells.ELIMINATE_COST)!!.shells)
        assertEquals(5, p.spend(4)!!.shellsEarned) // spending does not lower the total earned
    }

    @Test fun revealGivesAnUnknownLetter() {
        // مكتبة against مدرسة: positions 0 (م) and 4 (ة) are already green.
        var s = play(GameState(answer = "مدرسة"), "مكتبة")
        repeat(20) { seed ->
            val pos = PowerUps.revealPosition(s, Random(seed))!!
            assertTrue(pos in 1..3)
        }
        s = s.withReveal(1).withReveal(2).withReveal(3)
        assertNull(PowerUps.revealPosition(s))
        assertEquals(listOf('م', 'د', 'ر', 'س', 'ة'), s.knownLetters)
        assertEquals(LetterState.CORRECT, s.keyboard['د'])
        assertTrue(s.usedHelp)
    }

    @Test fun eliminateOnlyRemovesWrongUnknownLetters() {
        val s = play(GameState(answer = "مدرسة"), "سيارة")
        val gone = PowerUps.lettersToEliminate(s, 5, Random(1))
        assertEquals(5, gone.size)
        for (ch in gone) {
            assertFalse(ch in "مدرسة")
            assertFalse(ch in "سيارة")
        }
        val after = s.withEliminated(gone + 'م') // a correct letter is never removed
        assertEquals(gone, after.eliminated)
        for (ch in gone) assertEquals(LetterState.ABSENT, after.keyboard[ch])
        val back = SaveCodec.decodeGame(SaveCodec.encodeGame(after.withReveal(2)))!!
        assertEquals(after.eliminated, back.eliminated)
        assertEquals(setOf(2), back.revealed)
    }

    @Test fun rushSessionAddsTimeAndSaves() {
        var r = Rush.Session()
        r = r.tick(30_000).solvedWord("مدرسة")
        assertEquals(1, r.score)
        assertEquals(Rush.START_MS - 30_000 + Rush.BONUS_MS, r.remainingMs)
        assertEquals(r, Rush.decode(r.encode()))
        assertTrue(r.tick(10_000_000).isOver)
        var full = Rush.Session(remainingMs = Rush.MAX_MS - 1000)
        full = full.solvedWord("مكتبة")
        assertEquals(Rush.MAX_MS, full.remainingMs)
        val p = Progress().recordRush(listOf("مدرسة", "مكتبة"))
        assertEquals(2, p.bestRush)
        assertEquals(4, Progress(bestRush = 4).recordRush(listOf("مدرسة")).bestRush) // a lower score keeps the best
    }

    @Test fun badgesUnlockOnceWithRewards() {
        val s = play(GameState(answer = "مدرسة", playedMs = 20_000), "مدرسة")
        var p = Progress().recordRound(s)
        val first = Achievements.newlyEarned(p, s).map { it.id }
        assertEquals(listOf("first_win", "genius", "lightning"), first)
        p = Achievements.grant(p, Achievements.newlyEarned(p, s))
        assertEquals(5 + 15 + 5, p.shells)
        assertTrue(Achievements.newlyEarned(p, s).isEmpty())
        val back = SaveCodec.decodeProgress(SaveCodec.encodeProgress(p.copy(bestRush = 7, rushPlayed = 3)))!!
        assertEquals(p.badges, back.badges)
        assertEquals(p.shells, back.shells)
        assertEquals(7, back.bestRush)
        assertNotNull(Achievements.get("week"))
        assertEquals(Achievements.ALL.size, Achievements.ALL.map { it.id }.toSet().size)
    }
}
