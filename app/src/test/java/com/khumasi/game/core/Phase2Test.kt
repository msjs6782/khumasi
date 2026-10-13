package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2Test {

    private fun typeWord(s: GameState, w: String) = w.fold(s) { acc, c -> acc.type(c) }

    @Test fun hardModeKeepsGreensInPlace() {
        // answer مدرسة, guess مكتبة → م and ة are green.
        val msg = HardMode.violation("سيارة", listOf("مكتبة"), "مدرسة")
        assertEquals("الحرف الأول يجب أن يكون «م»", msg)
        assertNull(HardMode.violation("مدينة", listOf("مكتبة"), "مدرسة"))
    }

    @Test fun hardModeRequiresRevealedLetters() {
        // answer مدرسة, guess درجات → د and ر are amber.
        assertEquals("يجب أن تحتوي الكلمة على «د»", HardMode.violation("سيارة", listOf("درجات"), "مدرسة"))
        assertNull(HardMode.violation("مدرسة", listOf("درجات"), "مدرسة"))
    }

    @Test fun hardModeCountsRepeatedLetters() {
        // answer سلسلة; guess سسلاب → س green at 0, second س amber, ل amber.
        val g = listOf("سسلاب")
        assertEquals("يجب أن تحتوي الكلمة على «س» مرتين على الأقل", HardMode.violation("سلامة", g, "سلسلة"))
        assertNull(HardMode.violation("سلسلة", g, "سلسلة"))
    }

    @Test fun hardModeRejectsWithoutUsingAnAttempt() {
        val accepted = setOf("مكتبة", "سيارة", "مدينة", "مدرسة")
        var s = typeWord(GameState(answer = "مدرسة", hardMode = true), "مكتبة")
        s = (s.submit { it in accepted } as SubmitResult.Accepted).state
        val r = typeWord(s, "سيارة").submit { it in accepted }
        assertTrue(r is SubmitResult.Rejected && r.reason == RejectReason.HARD_MODE && r.detail != null)
        assertEquals(1, (r as SubmitResult.Rejected).state.guesses.size)
        assertTrue(typeWord(s, "مدينة").submit { it in accepted } is SubmitResult.Accepted)
    }

    @Test fun hardModeSurvivesSaving() {
        val s = GameState(answer = "مدرسة", hardMode = true)
        assertTrue(SaveCodec.decodeGame(SaveCodec.encodeGame(s))!!.hardMode)
        assertEquals(false, SaveCodec.decodeGame("v=1\nanswer=مدرسة")!!.hardMode)
    }

    @Test fun bestTimeAndAverage() {
        var p = Progress()
        p = p.recordRound(GameState(answer = "مدرسة", guesses = listOf("مكتبة", "مدرسة"), status = GameStatus.WON, playedMs = 90_000))
        p = p.recordRound(GameState(answer = "مدرسة", guesses = listOf("مكتبة", "حديقة", "سيارة", "مدرسة"), status = GameStatus.WON, playedMs = 60_000))
        p = p.recordRound(GameState(answer = "مدرسة", guesses = listOf("مدرسة"), status = GameStatus.WON, playedMs = 10_000, usedHelp = true))
        assertEquals(60_000L, p.bestWinMs)
        assertEquals(3f, p.unassisted.averageAttempts, 0.001f)
        assertEquals(60_000L, SaveCodec.decodeProgress(SaveCodec.encodeProgress(p))!!.bestWinMs)
    }

    @Test fun rewardsUnlockWithTheIsland() {
        assertEquals(listOf("ivory", "day"), Rewards.ALL.filter { Rewards.isUnlocked(it, 0) }.map { it.id })
        assertEquals(listOf("sunset"), Rewards.newlyUnlocked(2, 3).map { it.id })
        assertEquals("ivory", Rewards.resolve(Reward.Kind.TILES, "gold", 3).id)
        assertEquals("gold", Rewards.resolve(Reward.Kind.TILES, "gold", 9).id)
        assertTrue(Rewards.ALL.all { it.unlockLevel <= Island.MAX_LEVEL })
        assertEquals(Rewards.ALL.size, Rewards.ALL.map { it.kind to it.id }.toSet().size)
    }
}
