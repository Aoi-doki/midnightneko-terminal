package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rails every quest runs on, whoever wrote it — the rules planner or the AI.
 *
 * The System is harsh in tone and never in substance: loads rise slowly, caps scale with age and
 * body, injuries the Player describes are honoured, and nothing it asks for is ever about eating
 * less. Penalties are extra movement, never restriction.
 */
object SafetyLimits {

    /** Food restriction, purging, dehydration and similar. The System never says these things. */
    private val banned = Regex(
        "(\\bfasting\\b|\\b(water|juice|dry|intermittent|extended)\\s+fast|\\bfast\\s+(for|until|today|all\\s+day|\\d)|starv|" +
            "skip(ping)?\\s+(a\\s+|your\\s+)?(meal|breakfast|lunch|dinner)|don'?t\\s+eat|do\\s+not\\s+eat|" +
            "no\\s+food|without\\s+food|purg|vomit|throw\\s+up|laxative|diuretic|dehydrat|sauna\\s+suit|" +
            "(eat|consume)\\s+(less|under|below|fewer|nothing)|calorie\\s+(deficit|limit|cap)|\\b\\d{3,4}\\s*(k?cal|calories)\\b|" +
            "diet\\s+pill|appetite\\s+suppress)",
        RegexOption.IGNORE_CASE,
    )

    fun isSafeText(text: String): Boolean = !banned.containsMatchIn(text)

    fun ageCapSteps(age: Int): Int = when {
        age < 50 -> 25000
        age < 65 -> 18000
        else -> 12000
    }

    /** Hard ceiling for one day of this objective, for this Player. */
    fun cap(type: ObjectiveType, p: PlayerEntity): Int {
        val b = p.baselines
        val lim = p.limitations
        val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
        val heavy = bmi >= 35
        return when (type) {
            ObjectiveType.STEPS -> {
                val base = max(b.avgSteps, 3000)
                var c = min((base * 1.5).roundToInt() + 2000, ageCapSteps(p.age))
                if (lim.cardioCare) c = min(c, 8000)
                c
            }
            ObjectiveType.DISTANCE_M -> BodyMetrics.stepsToMeters(cap(ObjectiveType.STEPS, p), p.heightCm)
            ObjectiveType.BRISK_MIN -> when {
                lim.cardioCare -> 30
                heavy || p.age >= 65 -> 60
                else -> 90
            }
            ObjectiveType.PUSHUPS -> if (lim.upperBodyCare) 0 else min(max(b.pushups, 3) * 5, 150)
            ObjectiveType.SQUATS -> {
                val c = min(max(b.squats, 5) * 4, 200)
                if (lim.kneeCare) c / 2 else c
            }
            ObjectiveType.SITUPS -> if (lim.backCare) 0 else min(max(b.situps, 5) * 4, 150)
            ObjectiveType.PLANK_SEC -> {
                val c = min(max(b.plankSec, 15) * 4, 600)
                if (lim.upperBodyCare || lim.backCare) min(c, 120) else c
            }
            ObjectiveType.SLEEP_MIN -> 540
            ObjectiveType.MEDITATE_MIN -> 30
        }
    }

    fun floor(type: ObjectiveType): Int = when (type) {
        ObjectiveType.STEPS -> 2000
        ObjectiveType.DISTANCE_M -> 1000
        ObjectiveType.BRISK_MIN -> 5
        ObjectiveType.PUSHUPS, ObjectiveType.SQUATS, ObjectiveType.SITUPS -> 3
        ObjectiveType.PLANK_SEC -> 15
        ObjectiveType.SLEEP_MIN -> 420
        ObjectiveType.MEDITATE_MIN -> 3
    }

    fun allowed(type: ObjectiveType, p: PlayerEntity): Boolean = cap(type, p) > 0

    /**
     * Clamp one objective. [previous] is the highest target of this type in the last week, if any:
     * week over week a target may rise at most ~10% (with a small absolute step so tiny numbers move).
     */
    fun clampTarget(type: ObjectiveType, target: Int, p: PlayerEntity, previous: Int?): Int {
        val cap = cap(type, p)
        if (cap <= 0) return 0
        var t = target
        if (previous != null && previous > 0) {
            val step = when (type) {
                ObjectiveType.STEPS -> 500
                ObjectiveType.DISTANCE_M -> 400
                ObjectiveType.PLANK_SEC -> 10
                ObjectiveType.SLEEP_MIN -> 30
                else -> 2
            }
            t = min(t, max((previous * 1.10).roundToInt(), previous + step))
        }
        return t.coerceIn(min(floor(type), cap), cap)
    }

    /** Swap objectives the Player's body shouldn't do for ones it can. */
    fun substitute(type: ObjectiveType, p: PlayerEntity): ObjectiveType? {
        if (allowed(type, p)) return type
        val options = when (type) {
            ObjectiveType.PUSHUPS -> listOf(ObjectiveType.PLANK_SEC, ObjectiveType.BRISK_MIN)
            ObjectiveType.SITUPS -> listOf(ObjectiveType.BRISK_MIN, ObjectiveType.MEDITATE_MIN)
            ObjectiveType.SQUATS -> listOf(ObjectiveType.BRISK_MIN)
            else -> listOf(ObjectiveType.BRISK_MIN)
        }
        return options.firstOrNull { allowed(it, p) }
    }

    /** Apply every rail to a whole plan. Unknown/unsafe text is replaced, objectives clamped and deduplicated. */
    fun sanitize(plan: QuestPlan, p: PlayerEntity, previous: Map<ObjectiveType, Int>, fallbackFlavor: String): QuestPlan {
        val seen = mutableSetOf<ObjectiveType>()
        val objectives = plan.objectives.mapNotNull { o ->
            val type = substitute(o.type, p) ?: return@mapNotNull null
            if (!seen.add(type)) return@mapNotNull null
            val target = if (type == o.type) o.target else defaultTargetFor(type, p)
            ObjectivePlan(type, clampTarget(type, target, p, previous[type]))
        }.take(6)
        val title = plan.title.takeIf { it.isNotBlank() && isSafeText(it) }?.take(80) ?: "Daily Quest"
        val flavor = plan.flavor.takeIf { it.isNotBlank() && isSafeText(it) }?.take(400) ?: fallbackFlavor
        return plan.copy(
            title = title,
            flavor = flavor,
            objectives = objectives,
            xpReward = plan.xpReward.coerceIn(50, 2000),
        )
    }

    fun defaultTargetFor(type: ObjectiveType, p: PlayerEntity): Int = when (type) {
        ObjectiveType.PLANK_SEC -> max(p.baselines.plankSec, 30)
        ObjectiveType.BRISK_MIN -> 20
        ObjectiveType.MEDITATE_MIN -> 10
        ObjectiveType.SLEEP_MIN -> 450
        else -> floor(type) * 3
    }

    /** Goal weights under BMI 18.5 are raised to it. */
    fun safeGoalWeight(goalKg: Double, heightCm: Double): Double = max(goalKg, BodyMetrics.minHealthyWeight(heightCm))
}
