package com.khumasi.game.core

import java.text.Normalizer

/**
 * The published letter policy (docs/ARABIC_RULES.md). Must match tools/arabic_norm.py exactly;
 * both are checked against app/src/test/resources/normalization_vectors.tsv.
 *
 * - Diacritics (tashkeel), Quranic marks and tatweel are removed.
 * - أ إ آ ٱ ٲ ٳ all become ا.
 * - ء ؤ ئ stay separate letters; ة ≠ ه and ى ≠ ي.
 * - Word length is counted after normalization.
 */
object ArabicNormalizer {

    /** The 33 letters a normalized word may contain (also the in-game keyboard letters). */
    const val LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي" + "ءؤئىة"

    private val letterSet: Set<Char> = LETTERS.toSet()

    fun normalize(text: String): String {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(composed.length)
        for (ch in composed) {
            if (isRemoved(ch)) continue
            out.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> 'ا'
                    else -> ch
                }
            )
        }
        return out.toString()
    }

    fun isGameLetter(ch: Char): Boolean = ch in letterSet

    fun isClean(word: String): Boolean = word.isNotEmpty() && word.all { it in letterSet }

    private fun isRemoved(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x0610..0x061A) ||
            (c in 0x064B..0x065F) ||
            c == 0x0670 ||
            (c in 0x06D6..0x06DC) ||
            (c in 0x06DF..0x06E8) ||
            (c in 0x06EA..0x06ED) ||
            c == 0x0640 ||
            (c in 0x200B..0x200F) ||
            (c in 0x202A..0x202E) ||
            (c in 0x2066..0x2069) ||
            c == 0xFEFF ||
            Character.isWhitespace(ch)
    }
}
