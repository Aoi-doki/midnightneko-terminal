package dev.aoidoki.arise.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.aoidoki.arise.engine.Baselines
import dev.aoidoki.arise.engine.Limitations
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.engine.Rank
import kotlinx.serialization.Serializable

/** The one and only Player. Row id is always 1. */
@Serializable
@Entity(tableName = "player")
data class PlayerEntity(
    @PrimaryKey val id: Int = 1,
    val name: String = "Player",
    val age: Int = 25,
    val heightCm: Double = 175.0,
    val weightKg: Double = 80.0,
    val startWeightKg: Double = 80.0,
    val goalWeightKg: Double = 72.0,
    /** Free text: "Tell the System who you are". Never parsed into options — kept verbatim for the AI. */
    val about: String = "",
    /** Sex hint parsed from the player's own words: "m", "f" or "" when unknown. Only used for BMR. */
    val sexHint: String = "",

    val level: Int = 1,
    val xp: Int = 0,
    val freePoints: Int = 0,
    val str: Int = 10,
    val agi: Int = 10,
    val vit: Int = 10,
    val sen: Int = 10,
    val intel: Int = 10,
    val hp: Int = 200,
    val mp: Int = 110,
    val fatigue: Int = 0,

    val rank: Rank = Rank.E,
    val title: String = "None",
    val job: String = "None",
    val titles: List<String> = emptyList(),

    val streak: Int = 0,
    val bestStreak: Int = 0,
    val deaths: Int = 0,
    val questsCompleted: Int = 0,
    val questsFailed: Int = 0,

    val weakenedUntilDay: Long = -1,
    val recoveryMonth: Int = 0,
    val recoveryUsed: Int = 0,
    val recoveryDay: Long = -1,
    val trialCooldownUntilDay: Long = -1,

    val awakenedDay: Long = -1,
    val lastSettledDay: Long = -1,

    @Embedded(prefix = "base_") val baselines: Baselines = Baselines(),
    @Embedded(prefix = "lim_") val limitations: Limitations = Limitations(),
    /** The System's one-paragraph read of the player, written by the AI (or the rules fallback). */
    val assessment: String = "",
)

/** A stat the player defined themselves, in their own words: "max push-ups" → "22", "left knee" → "old ACL tear". */
@Serializable
@Entity(tableName = "custom_stat")
data class CustomStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val value: String,
    val position: Int = 0,
)

@Serializable
@Entity(tableName = "quest", indices = [Index("day"), Index("status")])
data class QuestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val kind: QuestKind,
    val status: QuestStatus = QuestStatus.ACTIVE,
    val title: String,
    val flavor: String,
    val source: String = "RULES",
    val xpReward: Int,
    val createdAt: Long,
    /** Tracking starts here (the penalty window may open later than the quest is created). */
    val startsAt: Long,
    val deadline: Long,
    val completedAt: Long = 0,
    val targetRank: Rank? = null,
)

@Serializable
@Entity(
    tableName = "objective",
    foreignKeys = [ForeignKey(entity = QuestEntity::class, parentColumns = ["id"], childColumns = ["questId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("questId")],
)
data class ObjectiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questId: Long,
    val type: ObjectiveType,
    val target: Int,
    val progress: Int = 0,
    /** The part of [progress] entered by hand rather than measured. Worth half XP. */
    val unverified: Int = 0,
    /** For window-based objectives (penalty steps): the day's step count when the window opened. -1 = not yet. */
    val baseline: Int = -1,
) {
    val fraction: Float get() = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
    val done: Boolean get() = progress >= target
}

data class QuestWithObjectives(
    @Embedded val quest: QuestEntity,
    @Relation(parentColumn = "id", entityColumn = "questId") val objectives: List<ObjectiveEntity>,
) {
    val completion: Float get() = if (objectives.isEmpty()) 0f else objectives.map { it.fraction }.average().toFloat()
    val allDone: Boolean get() = objectives.isNotEmpty() && objectives.all { it.done }
}

@Serializable
@Entity(tableName = "day_log")
data class DayLogEntity(
    @PrimaryKey val day: Long,
    val steps: Int = 0,
    val distanceM: Int = 0,
    val briskMin: Int = 0,
    val sleepMin: Int = 0,
    val completion: Float = 0f,
    val result: String = "OPEN",
    val xpGained: Int = 0,
    val xpLost: Int = 0,
    val hpLost: Int = 0,
)

@Serializable
@Entity(tableName = "weight", indices = [Index("time")])
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val kg: Double,
    val source: String = "MANUAL",
)

@Serializable
@Entity(tableName = "event", indices = [Index("time")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val type: String,
    val title: String,
    val message: String,
)
