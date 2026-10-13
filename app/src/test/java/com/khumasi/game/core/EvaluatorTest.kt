package com.khumasi.game.core

import com.khumasi.game.core.LetterState.ABSENT
import com.khumasi.game.core.LetterState.CORRECT
import com.khumasi.game.core.LetterState.PRESENT
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluatorTest {

    private fun eval(guess: String, answer: String) = Evaluator.evaluate(guess, answer)

    @Test fun exactMatchIsAllGreen() {
        assertEquals(List(5) { CORRECT }, eval("مدرسة", "مدرسة"))
    }

    @Test fun lettersInOtherPositionsAreAmber() {
        // تفاحة vs حافلة: ح ا ف are in the answer elsewhere, ل is not, ة is in place.
        assertEquals(listOf(PRESENT, PRESENT, PRESENT, ABSENT, CORRECT), eval("حافلة", "تفاحة"))
    }

    @Test fun repeatedGuessLetterBeyondAnswerCountIsGray() {
        assertEquals(listOf(CORRECT, ABSENT, ABSENT, ABSENT, ABSENT), eval("aaaaa", "abcde"))
    }

    @Test fun exactMatchesAreCountedBeforeMisplacedOnes() {
        // Position 1 is exact; the one 'a' left over goes to position 2.
        assertEquals(listOf(ABSENT, CORRECT, PRESENT, ABSENT, ABSENT), eval("xaaxx", "aabcd"))
    }

    @Test fun duplicateHandlingClassicCases() {
        assertEquals(listOf(PRESENT, PRESENT, CORRECT, CORRECT, ABSENT), eval("babes", "abbey"))
        assertEquals(listOf(ABSENT, PRESENT, CORRECT, PRESENT, PRESENT), eval("kebab", "abbey"))
        assertEquals(listOf(ABSENT, CORRECT, CORRECT, ABSENT, ABSENT), eval("bbbbb", "abbey"))
    }

    @Test fun misplacedDuplicatesAreHandedOutInReadingOrder() {
        // One 'b' in the answer, two misplaced in the guess: the first (rightmost on screen) is amber.
        assertEquals(listOf(PRESENT, ABSENT, ABSENT, ABSENT, ABSENT), eval("bxxbx", "abccc"))
    }

    @Test fun arabicDuplicateLetters() {
        // سلسلة has two س (both guessed in place) and no third.
        assertEquals(listOf(CORRECT, ABSENT, CORRECT, ABSENT, ABSENT), eval("سسسسس", "سلسلة"))
        // لؤلؤة: every ل and ؤ is misplaced but within the answer's counts.
        assertEquals(listOf(PRESENT, PRESENT, PRESENT, PRESENT, CORRECT), eval("ؤلؤلة", "لؤلؤة"))
        // Three ل guessed against two: one exact, one misplaced, the last one gray.
        assertEquals(listOf(PRESENT, CORRECT, ABSENT, ABSENT, ABSENT), eval("للذذل", "سلسلة"))
    }

    @Test fun keyboardNeverDowngrades() {
        var keys = KeyboardStates.merge(emptyMap(), "abcde", listOf(CORRECT, ABSENT, ABSENT, ABSENT, ABSENT))
        keys = KeyboardStates.merge(keys, "xaxxx", listOf(ABSENT, ABSENT, ABSENT, ABSENT, ABSENT))
        assertEquals(CORRECT, keys['a'])
        keys = KeyboardStates.merge(keys, "bbxxx", listOf(PRESENT, ABSENT, ABSENT, ABSENT, ABSENT))
        assertEquals(PRESENT, keys['b'])
        keys = KeyboardStates.merge(keys, "xxbxx", listOf(ABSENT, ABSENT, CORRECT, ABSENT, ABSENT))
        assertEquals(CORRECT, keys['b'])
        keys = KeyboardStates.merge(keys, "bxxxx", listOf(PRESENT, ABSENT, ABSENT, ABSENT, ABSENT))
        assertEquals(CORRECT, keys['b'])
    }

    @Test fun keyboardShowsAmberWhenAnExtraCopyIsGray() {
        val keys = KeyboardStates.fromGuesses(listOf("bxxbx"), "abccc")
        assertEquals(PRESENT, keys['b'])
        assertEquals(ABSENT, keys['x'])
    }
}
