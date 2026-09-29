package dev.aoidoki.arise.engine

import kotlinx.serialization.Serializable

/**
 * Hunter ranks, lowest to highest. A rank is earned by passing its Rank-Up Trial, which unlocks
 * once Hunter Power and level both clear the rank's bar.
 */
enum class Rank(val label: String, val minPower: Int, val minLevel: Int, val color: Long, val epithet: String) {
    E("E", 0, 1, 0xFF8A93A6, "The Weakest Hunter"),
    D("D", 20, 5, 0xFF4ADE80, "Awakened"),
    C("C", 35, 12, 0xFF38BDF8, "Seasoned Hunter"),
    B("B", 50, 20, 0xFF818CF8, "Elite Hunter"),
    A("A", 65, 30, 0xFFF472B6, "Guild Ace"),
    S("S", 80, 40, 0xFFFBBF24, "Calamity-Class"),
    NATIONAL("N", 92, 50, 0xFF8B5CF6, "National Level Hunter");

    val next: Rank? get() = entries.getOrNull(ordinal + 1)
    val displayName: String get() = if (this == NATIONAL) "National Level" else "$label-Rank"
}

enum class StatType(val short: String, val full: String) {
    STR("STR", "Strength"),
    AGI("AGI", "Agility"),
    VIT("VIT", "Vitality"),
    SEN("PER", "Perception"),
    INT("INT", "Intelligence"),
}

/** Everything a quest can ask for. Every type has an automatic tracker. */
enum class ObjectiveType(
    val label: String,
    val unit: String,
    val stat: StatType,
    val tracker: String,
) {
    STEPS("Walk", "steps", StatType.AGI, "Step sensor + Health Connect"),
    DISTANCE_M("Run / Walk distance", "m", StatType.AGI, "Steps × stride (from your height)"),
    BRISK_MIN("Brisk movement", "min", StatType.VIT, "Minutes above 100 steps/min"),
    PUSHUPS("Push-ups", "reps", StatType.STR, "Camera pose / proximity sensor"),
    SQUATS("Squats", "reps", StatType.STR, "Camera pose / accelerometer"),
    SITUPS("Sit-ups", "reps", StatType.VIT, "Camera pose / tilt sensor"),
    PLANK_SEC("Plank", "sec", StatType.VIT, "Stillness-checked timer"),
    SLEEP_MIN("Sleep", "min", StatType.SEN, "Health Connect sleep sessions"),
    MEDITATE_MIN("Meditation", "min", StatType.INT, "Stillness-checked timer");

    val isRep: Boolean get() = this == PUSHUPS || this == SQUATS || this == SITUPS
    val isTimed: Boolean get() = this == PLANK_SEC || this == MEDITATE_MIN

    fun format(value: Int): String = when (this) {
        DISTANCE_M -> if (value >= 1000) "%.2f km".format(value / 1000.0) else "$value m"
        SLEEP_MIN -> "%dh %02dm".format(value / 60, value % 60)
        PLANK_SEC -> if (value >= 60) "%d:%02d".format(value / 60, value % 60) else "${value}s"
        STEPS -> "%,d".format(value)
        else -> value.toString()
    }
}

enum class QuestKind { DAILY, PENALTY, TRIAL }

enum class QuestStatus { ACTIVE, COMPLETED, FAILED, EXCUSED }

/** Measured performance. Zero means "never measured"; the planners fall back to conservative defaults. */
@Serializable
data class Baselines(
    val pushups: Int = 0,
    val squats: Int = 0,
    val situps: Int = 0,
    val plankSec: Int = 0,
    val avgSteps: Int = 0,
    val assessedDay: Long = -1,
)

/** Things the System must respect when it writes quests, parsed from the player's own words. */
@Serializable
data class Limitations(
    val noJumping: Boolean = false,
    val kneeCare: Boolean = false,
    val backCare: Boolean = false,
    val upperBodyCare: Boolean = false,
    val cardioCare: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    val any: Boolean get() = noJumping || kneeCare || backCare || upperBodyCare || cardioCare
}

@Serializable
data class ObjectivePlan(val type: ObjectiveType, val target: Int)

@Serializable
data class QuestPlan(
    val kind: QuestKind,
    val title: String,
    val flavor: String,
    val objectives: List<ObjectivePlan>,
    val xpReward: Int,
    val source: String = "RULES",
)

/** A thing the System announces: shown as a window, spoken, notified, and written to the log. */
data class SystemEvent(
    val type: Type,
    val title: String,
    val message: String,
    val speech: String = message,
) {
    enum class Type {
        QUEST_ARRIVED, QUEST_PROGRESS, QUEST_COMPLETED, LEVEL_UP, POINTS, PENALTY_STARTED,
        PENALTY_CLEARED, PENALTY_FAILED, DEATH, RANK_TRIAL, RANK_UP, TITLE, WEIGHT, WARNING, INFO,
    }
}
