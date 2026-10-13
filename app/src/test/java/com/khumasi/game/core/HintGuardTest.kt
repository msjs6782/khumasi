package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HintGuardTest {
    @Test fun catchesTheAnswerInAnyDisguise() {
        val a = "مدرسة"
        assertTrue(HintGuard.revealsAnswer("إنها المَدْرَسَةُ طبعًا", a))
        assertTrue(HintGuard.revealsAnswer("الحروف: م د ر س ة", a))
        assertTrue(HintGuard.revealsAnswer("م-د-ر-س-ة", a))
        assertTrue(HintGuard.revealsAnswer("أسئلة", "اسئلة"))
        assertEquals(false, HintGuard.revealsAnswer("مكان يتعلم فيه الطلاب ويذهبون إليه صباحًا", a))
    }

    @Test fun fallbackHintsNeverContainTheWord() {
        val s = Solution("مدرسة", "مدرسة", "مكان", 1, "مكان يتعلم فيه الطلاب")
        for (l in 1..3) assertEquals(false, HintGuard.revealsAnswer(FallbackHints.hint(l, s, "مدرسة"), "مدرسة"))
        assertEquals("في الكلمة حرف مكرر.", FallbackHints.hint(2, null, "سلسلة"))
    }

    @Test fun completeLinesDropsCutTail() {
        val cut = "1) المعنى: جمع فائدة.\n2) الجذر اللغوي:\n3) مثال: نال فوا"
        assertEquals("1) المعنى: جمع فائدة.\n2) الجذر اللغوي:".substringBefore("\n2)"), HintGuard.completeLines(cut))
    }

    @Test fun tidyCutsAtSentenceEnd() {
        val t = "جملة أولى طويلة نسبيًا هنا. " + "كلمة ".repeat(40)
        assertEquals("جملة أولى طويلة نسبيًا هنا.", HintGuard.tidy(t, 50))
    }
}
