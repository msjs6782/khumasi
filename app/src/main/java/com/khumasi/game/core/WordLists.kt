package com.khumasi.game.core

/**
 * Accepted guesses of one length, packed into a single sorted CharArray so 120k+ words take
 * about 1.2 MB and lookups are a binary search with no allocation.
 */
class GuessList private constructor(val length: Int, private val packed: CharArray) {

    val size: Int get() = packed.size / length

    operator fun contains(word: String): Boolean {
        if (word.length != length) return false
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareAt(mid, word)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    private fun compareAt(index: Int, word: String): Int {
        val base = index * length
        for (i in 0 until length) {
            val d = packed[base + i] - word[i]
            if (d != 0) return d
        }
        return 0
    }

    companion object {
        /**
         * Fast path for the bundled file: one pass over the whole text, no per-line strings, and
         * no sorting when the file is already sorted (the build tool writes it sorted).
         */
        fun fromText(length: Int, text: CharSequence, extra: Collection<String> = emptyList()): GuessList {
            var packed = CharArray(140_000 * length)
            var n = 0
            var sorted = true
            var i = 0
            val end = text.length
            while (i < end) {
                var j = i
                while (j < end && text[j] != '\n') j++
                var lineEnd = j
                if (lineEnd > i && text[lineEnd - 1] == '\r') lineEnd--
                if (lineEnd - i == length && text[i] != '#') {
                    if ((n + 1) * length > packed.size) packed = packed.copyOf(packed.size * 2)
                    val base = n * length
                    for (k in 0 until length) packed[base + k] = text[i + k]
                    if (n > 0 && sorted) {
                        var cmp = 0
                        for (k in 0 until length) {
                            cmp = packed[base + k] - packed[base - length + k]
                            if (cmp != 0) break
                        }
                        if (cmp <= 0) sorted = false
                    }
                    n++
                }
                i = j + 1
            }
            val missing = extra.filter { it.length == length }
            if (!sorted || missing.isNotEmpty()) {
                // Rare path: merge extras / repair order through the general builder.
                val words = ArrayList<String>(n + missing.size)
                for (w in 0 until n) words.add(String(packed, w * length, length))
                return fromLines(length, words.asSequence(), missing)
            }
            return GuessList(length, packed.copyOf(n * length))
        }

        /** Builds from text lines; comment lines (#) and words of other lengths are skipped. */
        fun fromLines(length: Int, lines: Sequence<String>, extra: Collection<String> = emptyList()): GuessList {
            val words = ArrayList<String>(130_000)
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                if (line.length == length) words.add(line)
            }
            for (w in extra) if (w.length == length) words.add(w)
            words.sort()
            val packed = CharArray(words.size * length)
            var n = 0
            var previous: String? = null
            for (w in words) {
                if (w == previous) continue
                w.toCharArray(packed, n * length, 0, length)
                n++
                previous = w
            }
            return GuessList(length, if (n * length == packed.size) packed else packed.copyOf(n * length))
        }
    }
}

data class Solution(
    val word: String,
    val display: String,
    val category: String,
    val difficulty: Int,
    val meaning: String,
)

object Solutions {
    /** Parses solutionsN.tsv: normalized, display, category, difficulty, meaning. */
    fun parse(lines: Sequence<String>): List<Solution> = lines
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            val word = p[0].trim()
            if (!ArabicNormalizer.isClean(word)) return@mapNotNull null
            Solution(word, p[1].trim(), p[2].trim(), p[3].trim().toIntOrNull() ?: 2, p[4].trim())
        }
        .toList()
}
