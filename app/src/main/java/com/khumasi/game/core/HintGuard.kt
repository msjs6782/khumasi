package com.khumasi.game.core

/**
 * Checks AI text before the player sees it. A hint must never reveal the answer, even with
 * diacritics, the definite article, spaces or dashes between letters, or alef variants.
 */
object HintGuard {

    fun revealsAnswer(text: String, answer: String): Boolean {
        val target = ArabicNormalizer.normalize(answer)
        if (target.isEmpty()) return false
        val norm = ArabicNormalizer.normalize(text)
        if (norm.contains(target)) return true
        // Letters spelled out one by one: drop everything that is not a game letter.
        val spaced = Regex("[\\p{L}](?:[\\s\\-ـ.,،]+[\\p{L}]){${target.length - 1},}")
        for (m in spaced.findAll(norm)) {
            val joined = m.value.filter { ArabicNormalizer.isGameLetter(it) }
            if (joined.contains(target)) return true
        }
        return false
    }

    /** Keeps a short, clean hint: first lines only, no markdown. */
    fun tidy(text: String, maxChars: Int = 280): String {
        val t = text.replace(Regex("[*#`_]+"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (t.length <= maxChars) t else t.take(maxChars).substringBeforeLast(' ') + "…"
    }
}

/** Offline hints used when there is no key, no network, or the AI hint fails the checks. */
object FallbackHints {
    fun hint(level: Int, solution: Solution?, answer: String): String = when (level) {
        1 -> "التصنيف: ${solution?.category ?: "كلمة عامة"}."
        2 -> {
            val distinct = answer.toSet().size
            if (distinct < answer.length) "في الكلمة حرف مكرر." else "حروف الكلمة الخمسة كلها مختلفة."
        }
        else -> solution?.meaning?.let { "المعنى: $it" } ?: "الحرف الأول هو «${answer.first()}»."
    }
}
