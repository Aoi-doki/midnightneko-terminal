package dev.aoidoki.arise.data

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whole-save export/import as one JSON file, so a reinstall or a new phone never costs progress. */
object Backup {
    @Serializable
    data class Save(
        val version: Int = 1,
        val player: PlayerEntity?,
        val customStats: List<CustomStatEntity>,
        val quests: List<QuestEntity>,
        val objectives: List<ObjectiveEntity>,
        val days: List<DayLogEntity>,
        val weights: List<WeightEntity>,
        val events: List<EventEntity>,
        val inventory: List<InventoryEntity> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    suspend fun export(db: AriseDatabase): String {
        val save = Save(
            player = db.player().get(),
            customStats = db.player().customStats(),
            quests = db.quests().allQuests(),
            objectives = db.quests().allObjectives(),
            days = db.logs().allDays(),
            weights = db.logs().weights(),
            events = db.logs().events(),
            inventory = db.player().inventory(),
        )
        return json.encodeToString(save)
    }

    suspend fun import(db: AriseDatabase, text: String): Boolean {
        val save = runCatching { json.decodeFromString<Save>(text) }.getOrNull() ?: return false
        val player = save.player ?: return false
        val m = db.maintenance()
        db.withTransaction {
            m.wipe()
            m.insertPlayer(player)
            m.insertCustom(save.customStats)
            m.insertQuests(save.quests)
            m.insertObjectives(save.objectives)
            m.insertDays(save.days)
            m.insertWeights(save.weights)
            m.insertEvents(save.events)
            m.insertInventory(save.inventory)
        }
        return true
    }
}
