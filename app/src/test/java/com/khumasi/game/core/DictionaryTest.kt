package com.khumasi.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the real word lists shipped in the APK and the shared normalization policy. */
class DictionaryTest {

    private fun asset(name: String): File {
        // Unit tests run with the module directory as working directory.
        val candidates = listOf(File("src/main/assets/dict/$name"), File("app/src/main/assets/dict/$name"))
        return candidates.first { it.exists() }
    }

    private fun resource(name: String): File =
        listOf(File("src/test/resources/$name"), File("app/src/test/resources/$name")).first { it.exists() }

    private val guesses by lazy { GuessList.fromLines(5, asset("guesses5.txt").readLines().asSequence()) }
    private val solutions by lazy { Solutions.parse(asset("solutions5.tsv").readLines().asSequence()) }

    @Test fun normalizationMatchesThePythonReference() {
        val lines = resource("normalization_vectors.tsv").readLines().filter { it.isNotEmpty() }
        assertTrue(lines.size >= 10)
        for (line in lines) {
            val (raw, expected) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
            assertEquals("normalize($raw)", expected, ArabicNormalizer.normalize(raw))
        }
    }

    @Test fun keyboardHas33DistinctLetters() {
        assertEquals(33, ArabicNormalizer.LETTERS.length)
        assertEquals(33, ArabicNormalizer.LETTERS.toSet().size)
    }

    @Test fun guessListIsLargeAndClean() {
        assertTrue("size ${guesses.size}", guesses.size > 100_000)
        assertTrue("مدرسة" in guesses)
        assertTrue("كتبوا" in guesses)
        assertFalse("المدرسة" in guesses)
        assertFalse("ممممم" in guesses)
    }

    @Test fun solutionsAreValidUniqueAndAccepted() {
        assertTrue("count ${solutions.size}", solutions.size >= 500)
        val seen = HashSet<String>()
        for (s in solutions) {
            assertEquals(s.word, 5, s.word.length)
            assertTrue(s.word, ArabicNormalizer.isClean(s.word))
            assertEquals(s.word, ArabicNormalizer.normalize(s.display), s.word)
            assertTrue("duplicate ${s.word}", seen.add(s.word))
            assertTrue("not a guess: ${s.word}", s.word in guesses)
            assertTrue(s.word, s.meaning.isNotBlank())
            assertTrue(s.word, s.difficulty in 1..3)
        }
        assertTrue(solutions.count { it.difficulty == 1 } >= 50)
    }

    @Test fun guessListLookupWorksOnUnsortedInputWithDuplicates() {
        val list = GuessList.fromLines(3, sequenceOf("# c", "ccc", "aaa", "bbb", "aaa", "toolong"), listOf("zzz"))
        assertEquals(4, list.size)
        for (w in listOf("aaa", "bbb", "ccc", "zzz")) assertTrue(w in list)
        assertFalse("abc" in list)
        assertFalse("aa" in list)
    }
}
