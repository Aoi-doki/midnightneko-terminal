package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity

/** Titles are earned, never chosen from a list before they're earned. */
object Titles {
    data class Title(val name: String, val description: String)

    val WOLF_SLAYER = Title("Wolf Slayer", "Completed 7 Daily Quests in a row.")
    val ONE_WHO_OVERCAME = Title("The One Who Overcame Adversity", "Cleared a Penalty Quest.")
    val IRON_BODY = Title("Iron Body", "Lost your first 5 kg.")
    val GOAL = Title("Demon Hunter", "Reached your goal weight.")
    val RELENTLESS = Title("Relentless", "Completed 30 Daily Quests in a row.")
    val REBORN = Title("Reborn", "Died and came back.")
    val FIRST_STEP = Title("The First Step", "Lost your first kilogram.")
    val MONARCH = Title("Shadow Monarch", "Reached National Level.")

    /** Titles the Player qualifies for now but doesn't have yet. */
    fun newlyEarned(p: PlayerEntity, clearedPenalty: Boolean = false): List<Title> {
        val lost = p.startWeightKg - p.weightKg
        val earned = buildList {
            if (p.streak >= 7) add(WOLF_SLAYER)
            if (p.streak >= 30) add(RELENTLESS)
            if (clearedPenalty) add(ONE_WHO_OVERCAME)
            if (lost >= 1.0) add(FIRST_STEP)
            if (lost >= 5.0) add(IRON_BODY)
            if (p.goalWeightKg < p.startWeightKg && p.weightKg <= p.goalWeightKg) add(GOAL)
            if (p.deaths > 0) add(REBORN)
            if (p.rank == Rank.NATIONAL) add(MONARCH)
        }
        return earned.filter { it.name !in p.titles }
    }

    fun describe(name: String): String =
        listOf(WOLF_SLAYER, ONE_WHO_OVERCAME, IRON_BODY, GOAL, RELENTLESS, REBORN, FIRST_STEP, MONARCH)
            .firstOrNull { it.name == name }?.description ?: ""
}
