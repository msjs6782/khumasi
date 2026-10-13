package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTest {

    private val accepted = setOf("مدرسة", "مكتبة", "حديقة", "سيارة", "طائرة", "جزيرة", "مدينة", "عاصمة", "جامعة")
    private fun typeWord(s: GameState, w: String) = w.fold(s) { acc, c -> acc.type(c) }

    @Test fun typingStopsAtWordLengthAndIgnoresOtherCharacters() {
        var s = GameState(answer = "مدرسة")
        s = typeWord(s, "مكتبةز")
        assertEquals("مكتبة", s.input)
        assertSame(s, s.type('x'))
        assertEquals("مكتب", s.deleteLetter().input)
    }

    @Test fun shortWordIsRejectedWithoutUsingAnAttempt() {
        val s = typeWord(GameState(answer = "مدرسة"), "مكت")
        val r = s.submit { it in accepted }
        assertTrue(r is SubmitResult.Rejected && r.reason == RejectReason.TOO_SHORT)
        assertEquals(0, (r as SubmitResult.Rejected).state.guesses.size)
        assertEquals("مكت", r.state.input)
    }

    @Test fun unknownWordIsRejectedWithoutUsingAnAttempt() {
        val s = typeWord(GameState(answer = "مدرسة"), "ممممم")
        val r = s.submit { it in accepted }
        assertTrue(r is SubmitResult.Rejected && r.reason == RejectReason.NOT_IN_LIST)
        assertEquals(0, (r as SubmitResult.Rejected).state.currentRow)
    }

    @Test fun repeatedGuessIsRejected() {
        var s = typeWord(GameState(answer = "مدرسة"), "مكتبة")
        s = (s.submit { it in accepted } as SubmitResult.Accepted).state
        val r = typeWord(s, "مكتبة").submit { it in accepted }
        assertTrue(r is SubmitResult.Rejected && r.reason == RejectReason.ALREADY_TRIED)
    }

    @Test fun answerIsAlwaysAccepted() {
        val s = typeWord(GameState(answer = "لؤلؤة"), "لؤلؤة")
        val r = s.submit { false }
        assertTrue(r is SubmitResult.Accepted)
        assertEquals(GameStatus.WON, (r as SubmitResult.Accepted).state.status)
    }

    @Test fun winningEndsTheRound() {
        var s = typeWord(GameState(answer = "مدرسة"), "مكتبة")
        s = (s.submit { it in accepted } as SubmitResult.Accepted).state
        s = typeWord(s, "مدرسة")
        val r = s.submit { it in accepted } as SubmitResult.Accepted
        assertEquals(GameStatus.WON, r.state.status)
        assertEquals(2, r.state.guesses.size)
        assertSame(r.state, r.state.type('م'))
    }

    @Test fun eightWrongGuessesLose() {
        val words = accepted.filter { it != "مدرسة" }.take(8)
        var s = GameState(answer = "مدرسة")
        for ((i, w) in words.withIndex()) {
            val r = typeWord(s, w).submit { it in accepted } as SubmitResult.Accepted
            s = r.state
            assertEquals(if (i == 7) GameStatus.LOST else GameStatus.PLAYING, s.status)
        }
        assertTrue(s.isOver)
        assertEquals(8, s.guesses.size)
    }

    @Test fun keyboardIsDerivedFromGuesses() {
        var s = typeWord(GameState(answer = "مدرسة"), "مكتبة")
        s = (s.submit { it in accepted } as SubmitResult.Accepted).state
        assertEquals(LetterState.CORRECT, s.keyboard['م'])
        assertEquals(LetterState.ABSENT, s.keyboard['ك'])
        assertEquals(LetterState.CORRECT, s.keyboard['ة'])
        assertFalse(s.keyboard.containsKey('د'))
    }

    @Test fun playTimeOnlyAddsWhilePlaying() {
        val s = GameState(answer = "مدرسة").addPlayTime(1500)
        assertEquals(1500, s.playedMs)
        val won = (typeWord(s, "مدرسة").submit { true } as SubmitResult.Accepted).state
        assertEquals(1500, won.addPlayTime(999).playedMs)
    }
}
