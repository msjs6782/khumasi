package com.khumasi.game.core

/**
 * Cosmetic rewards unlocked by the island level. They change only looks, never the game.
 * Descriptions are short and hand-written (Gemini may write richer ones in phase 3).
 */
data class Reward(
    val id: String,
    val kind: Kind,
    val name: String,
    val description: String,
    val unlockLevel: Int,
) {
    enum class Kind { TILES, SKY }
}

object Rewards {
    val ALL: List<Reward> = listOf(
        Reward("ivory", Reward.Kind.TILES, "عاجي", "قطع بلون العاج وحبر كحلي، الإطلالة الكلاسيكية.", 0),
        Reward("sand", Reward.Kind.TILES, "رملي", "قطع بلون رمال الشاطئ الدافئة.", 2),
        Reward("marble", Reward.Kind.TILES, "رخامي", "بياض الرخام مع حبر رمادي داكن.", 4),
        Reward("night", Reward.Kind.TILES, "ليلي", "قطع داكنة بحروف ذهبية تليق بالسهر.", 6),
        Reward("gold", Reward.Kind.TILES, "ذهبي", "قطع مذهّبة لمن أكمل معالم جزيرته.", 9),
        Reward("day", Reward.Kind.SKY, "نهار", "سماء صافية وبحر فيروزي.", 0),
        Reward("sunset", Reward.Kind.SKY, "غروب", "شمس تميل إلى البحر وسماء برتقالية.", 3),
        Reward("night", Reward.Kind.SKY, "ليل", "قمر هادئ ونجوم فوق الجزيرة.", 5),
        Reward("dawn", Reward.Kind.SKY, "فجر", "ألوان وردية رقيقة قبل الشروق.", 7),
        Reward("aurora", Reward.Kind.SKY, "شفق", "ستائر خضراء من الضوء ترقص في السماء.", 10),
    )

    fun of(kind: Reward.Kind): List<Reward> = ALL.filter { it.kind == kind }

    fun isUnlocked(r: Reward, islandLevel: Int): Boolean = islandLevel >= r.unlockLevel

    /** Rewards that became available when the island went from [before] to [after]. */
    fun newlyUnlocked(before: Int, after: Int): List<Reward> =
        ALL.filter { it.unlockLevel in (before + 1)..after }

    /** A chosen reward that is not (or no longer) available falls back to the default. */
    fun resolve(kind: Reward.Kind, id: String?, islandLevel: Int): Reward {
        val list = of(kind)
        return list.firstOrNull { it.id == id && isUnlocked(it, islandLevel) } ?: list.first()
    }
}
