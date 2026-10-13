package com.khumasi.game.core

import kotlin.random.Random

/**
 * The daily challenge: one word per calendar day, the same on every phone with the same word
 * list, with no server. Day 1 is 1 October 2026.
 */
object Daily {
    const val FIRST_EPOCH_DAY = 20727L
    private const val DAY_MS = 86_400_000L
    private const val SEED = 0x6B68756DL // fixed forever: changing it changes everyone's words

    /** Local calendar day (days since 1970-01-01) for a time and the device's UTC offset. */
    fun localEpochDay(nowMs: Long, offsetMs: Int): Long = Math.floorDiv(nowMs + offsetMs, DAY_MS)

    /** Never below 1, even if the phone's date is set before the first day. */
    fun number(epochDay: Long): Int = (epochDay - FIRST_EPOCH_DAY + 1).toInt().coerceAtLeast(1)

    /** Milliseconds until the next local midnight, when a new challenge opens. */
    fun msUntilNext(nowMs: Long, offsetMs: Int): Long {
        val local = nowMs + offsetMs
        return DAY_MS - Math.floorMod(local, DAY_MS)
    }

    /**
     * The word for a day number. Words are put in a fixed shuffled order (from a sorted copy, so
     * the order of the file does not matter) and taken one per day; after the last one the cycle
     * starts again.
     */
    fun word(number: Int, solutions: List<Solution>): Solution {
        require(solutions.isNotEmpty()) { "No solutions" }
        val sorted = solutions.sortedBy { it.word }
        val order = sorted.indices.toMutableList().also { shuffle(it, Random(SEED)) }
        val i = Math.floorMod(number - 1, sorted.size)
        return sorted[order[i]]
    }

    /** Fisher–Yates with our own loop so the order never depends on library internals. */
    private fun shuffle(list: MutableList<Int>, random: Random) {
        for (i in list.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }
}

/**
 * A friend challenge as a short code such as KH-ABCD-EFGH-JKLMN. The word is scrambled so the
 * code does not show it, and a check byte catches typing mistakes. Works without a server: the
 * code travels in any chat app.
 */
object FriendCode {
    private const val VERSION = 1
    private const val PREFIX = "KH"
    private const val SYMBOLS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I, O, 0, 1
    private const val KEY = 0x2F6A1D3L
    private val CODE = Regex("KH[\\s-]*([A-Z2-9][A-Z2-9\\s-]{11,30})", RegexOption.IGNORE_CASE)

    fun encode(word: String, salt: Int = Random.nextInt(256)): String {
        require(word.all { ArabicNormalizer.isGameLetter(it) } && word.length in 2..7) { "Bad word" }
        val plain = ByteArray(word.length + 3)
        plain[0] = (VERSION * 16 + word.length).toByte()
        plain[1] = salt.toByte()
        for (i in word.indices) plain[2 + i] = ArabicNormalizer.LETTERS.indexOf(word[i]).toByte()
        plain[plain.lastIndex] = check(plain, plain.lastIndex)
        val bytes = mix(plain)
        val body = toBase32(bytes)
        // Groups of four; a short last group joins the one before it (KH-ABCD-EFGH-JKLMN).
        val groups = body.chunked(4).toMutableList()
        if (groups.size > 1 && groups.last().length < 4) groups[groups.size - 2] += groups.removeAt(groups.lastIndex)
        return PREFIX + "-" + groups.joinToString("-")
    }

    /** Finds a code anywhere in the text (a pasted message, for example); null if none is valid. */
    fun decode(text: String): String? {
        for (m in CODE.findAll(text)) {
            val body = m.groupValues[1].uppercase().filter { it in SYMBOLS }
            for (len in body.length downTo 10) {
                val word = tryDecode(body.take(len))
                if (word != null) return word
            }
        }
        return null
    }

    private fun tryDecode(body: String): String? {
        val bytes = fromBase32(body) ?: return null
        if (bytes.size < 5) return null
        val plain = mix(bytes)
        val head = plain[0].toInt() and 0xFF
        val length = head and 0x0F
        if (head shr 4 != VERSION || plain.size != length + 3) return null
        if (plain[plain.lastIndex] != check(plain, plain.lastIndex)) return null
        val sb = StringBuilder()
        for (i in 0 until length) {
            val idx = plain[2 + i].toInt() and 0xFF
            if (idx >= ArabicNormalizer.LETTERS.length) return null
            sb.append(ArabicNormalizer.LETTERS[idx])
        }
        return sb.toString()
    }

    private fun check(b: ByteArray, n: Int): Byte {
        var c = 0x5A
        for (i in 0 until n) c = (c * 31 + (b[i].toInt() and 0xFF)) and 0xFF
        return c.toByte()
    }

    // The salt byte stays readable; every other byte is mixed with a stream seeded by it.
    // XOR is its own inverse, so the same function scrambles and unscrambles.
    private fun mix(src: ByteArray): ByteArray {
        val r = Random(KEY + (src[1].toInt() and 0xFF))
        val out = src.copyOf()
        for (i in out.indices) if (i != 1) out[i] = (out[i].toInt() xor r.nextInt(256)).toByte()
        return out
    }

    private fun toBase32(b: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(SYMBOLS[(buf shr bits) and 31]) }
        }
        if (bits > 0) sb.append(SYMBOLS[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun fromBase32(s: String): ByteArray? {
        val out = ArrayList<Byte>()
        var buf = 0; var bits = 0
        for (ch in s) {
            val v = SYMBOLS.indexOf(ch)
            if (v < 0) return null
            buf = ((buf shl 5) or v) and 0xFFFF; bits += 5
            if (bits >= 8) { bits -= 8; out.add(((buf shr bits) and 0xFF).toByte()) }
        }
        return out.toByteArray()
    }
}

/**
 * A backup of the game's saved settings and progress as text, so it can be kept in a file (on
 * Google Drive, for example) and restored on this or another phone.
 */
object Backup {
    const val HEADER = "khumasi-backup"
    const val VERSION = 1
    private const val SET_SEP = '\u001F'

    fun encode(values: Map<String, *>, createdMs: Long): String {
        val m = linkedMapOf(HEADER to VERSION.toString(), "created" to createdMs.toString())
        for ((k, v) in values.toSortedMap()) {
            when (v) {
                is String -> m["s.$k"] = v
                is Boolean -> m["b.$k"] = if (v) "1" else "0"
                is Int -> m["i.$k"] = v.toString()
                is Long -> m["l.$k"] = v.toString()
                is Float -> m["f.$k"] = v.toString()
                is Set<*> -> m["S.$k"] = v.filterIsInstance<String>().sorted().joinToString(SET_SEP.toString())
            }
        }
        return SaveCodec.encode(m)
    }

    class Contents(val values: Map<String, Any>, val createdMs: Long)

    /** Null when the text is not a backup from this game or comes from a newer version. */
    fun decode(text: String): Contents? = runCatching {
        val m = SaveCodec.decode(text.trim().removePrefix("﻿"))
        val v = m[HEADER]?.toIntOrNull() ?: return null
        if (v > VERSION) return null
        val out = LinkedHashMap<String, Any>()
        for ((k, raw) in m) {
            if (k.length < 3 || k[1] != '.') continue
            val key = k.substring(2)
            when (k[0]) {
                's' -> out[key] = raw
                'b' -> out[key] = raw == "1"
                'i' -> raw.toIntOrNull()?.let { out[key] = it }
                'l' -> raw.toLongOrNull()?.let { out[key] = it }
                'f' -> raw.toFloatOrNull()?.let { out[key] = it }
                'S' -> out[key] = if (raw.isEmpty()) emptySet<String>() else raw.split(SET_SEP).toSet()
            }
        }
        Contents(out, m["created"]?.toLongOrNull() ?: 0L)
    }.getOrNull()
}
