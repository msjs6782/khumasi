package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrambleTest {

    private val puzzles: List<ScramblePuzzle> by lazy {
        val f = listOf("app/src/main/assets/dict/scramble.tsv", "src/main/assets/dict/scramble.tsv", "../app/src/main/assets/dict/scramble.tsv")
            .map { File(it) }.first { it.exists() }
        ScrambleList.parse(f.readLines(Charsets.UTF_8).asSequence())
    }

    @Test fun bundledListLoads() {
        assertTrue(puzzles.size > 500)
        for (len in 3..7) assertTrue("length $len", puzzles.any { it.word.length == len })
        val lion = puzzles.first { it.display == "أسد" }
        assertEquals("اسد", lion.word)
        assertEquals("حيوان", lion.category)
        assertTrue(lion.accepts("اسد"))
        assertTrue(lion.accepts("ساد")) // a common word with the same letters also counts
        assertFalse(lion.accepts("دسا"))
    }

    @Test fun stagesGrowLongerAndStayFixed() {
        assertEquals(3, ScrambleLevels.lengthFor(1))
        assertEquals(4, ScrambleLevels.lengthFor(6))
        assertEquals(5, ScrambleLevels.lengthFor(20))
        assertEquals(6, ScrambleLevels.lengthFor(40))
        assertEquals(7, ScrambleLevels.lengthFor(60))
        for (s in listOf(1, 7, 30, 50, 61, 200)) {
            val p = ScrambleLevels.puzzleFor(s, puzzles)
            assertEquals(p, ScrambleLevels.puzzleFor(s, puzzles.shuffled()))
            assertEquals(ScrambleLevels.lengthFor(s), p.word.length)
        }
        // The first five stages are five different words.
        assertEquals(5, (1..5).map { ScrambleLevels.puzzleFor(it, puzzles).word }.toSet().size)
    }

    @Test fun scrambleIsNeverAlreadySolved() {
        for (s in 1..300) {
            val p = ScrambleLevels.puzzleFor(s, puzzles)
            val mixed = ScrambleLevels.scrambled(p, s)
            assertEquals(p.word.toList().sorted(), mixed.toList().sorted())
            assertFalse("stage $s: $mixed", p.accepts(mixed))
        }
        val p = ScrambleLevels.puzzleFor(3, puzzles)
        assertEquals(ScrambleLevels.scrambled(p, 3), ScrambleLevels.scrambled(p, 3))
        assertNotEquals(ScrambleLevels.scrambled(p, 3, 0), ScrambleLevels.scrambled(p, 3, 1).takeIf { p.word.length > 3 } ?: "")
    }

    @Test fun starsAndProgress() {
        assertEquals(3, ScrambleLevels.stars(0, 0))
        assertEquals(2, ScrambleLevels.stars(1, 0))
        assertEquals(2, ScrambleLevels.stars(0, 1))
        assertEquals(1, ScrambleLevels.stars(2, 3))
        var p = Progress()
        repeat(10) { p = p.recordScramble(3) }
        assertEquals(11, p.scrambleStage)
        assertEquals(30, p.scrambleStars)
        assertTrue(Achievements.newlyEarned(p, null).any { it.id == "scramble10" })
        val back = SaveCodec.decodeProgress(SaveCodec.encodeProgress(p))!!
        assertEquals(11, back.scrambleStage)
        assertEquals(1, SaveCodec.decodeProgress("v=1")!!.scrambleStage) // older saves start at stage 1
    }
}
