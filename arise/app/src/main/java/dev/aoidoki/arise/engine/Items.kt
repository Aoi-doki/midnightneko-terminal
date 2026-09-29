package dev.aoidoki.arise.engine

enum class Rarity(val label: String, val color: Long) {
    COMMON("Common", 0xFFA0AEC0),
    RARE("Rare", 0xFF00D2FF),
    EPIC("Epic", 0xFFB06BFF),
    LEGENDARY("Legendary", 0xFFFFC857),
}

/**
 * Everything the Shop sells. Items only ever restore or protect; nothing skips real work,
 * so the gold loop can't be used to buy your way out of training.
 */
enum class Item(
    val id: String,
    val label: String,
    val rarity: Rarity,
    val price: Int,
    val description: String,
    val effect: String,
) {
    HEALING_POTION(
        "healing_potion", "Healing Potion", Rarity.COMMON, 60,
        "A vial of red light. Tastes of iron.", "Restores 30% of max HP.",
    ),
    MANA_CRYSTAL(
        "mana_crystal", "Mana Crystal", Rarity.COMMON, 60,
        "A shard that hums when held.", "Restores 30 MP.",
    ),
    STAMINA_TONIC(
        "stamina_tonic", "Stamina Tonic", Rarity.RARE, 120,
        "Brewed from a boss's reagent glands.", "Reduces fatigue by 40.",
    ),
    STREAK_WARD(
        "streak_ward", "Ward of Continuity", Rarity.EPIC, 400,
        "A seal that remembers your discipline.",
        "The next failed Daily Quest keeps your streak. The penalty still applies.",
    ),
    ELIXIR_OF_LIFE(
        "elixir_of_life", "Elixir of Life", Rarity.LEGENDARY, 1000,
        "Said to have been brewed for a dying mother.",
        "Fully restores HP and MP and cures Weakened.",
    );

    companion object {
        fun byId(id: String): Item? = entries.firstOrNull { it.id == id }
    }
}

object Gold {
    fun forDaily(rank: Rank): Int = 20 + 5 * rank.ordinal
    const val PENALTY_SURVIVED = 15
    const val PER_KG_LOST = 30
    fun forTrial(target: Rank): Int = 100 + 50 * target.ordinal
    const val STREAK_WEEK_BONUS = 50
}
