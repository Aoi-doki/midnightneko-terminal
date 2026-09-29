package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The deterministic quest writer. It is always available, needs no model, and is what the AI's
 * output falls back to whenever that output is missing, malformed or unsafe.
 */
object QuestPlanner {

    data class Context(
        val player: PlayerEntity,
        val avgSteps7d: Int,
        /** Highest target per type over the last 7 days, for progression limits. */
        val previousTargets: Map<ObjectiveType, Int>,
        /** Fraction of the last 7 daily quests completed. */
        val recentCompletion: Double,
        val dayOfWeek: Int,
    )

    private val rankMultiplier = mapOf(
        Rank.E to 1.0, Rank.D to 1.1, Rank.C to 1.2, Rank.B to 1.35, Rank.A to 1.5, Rank.S to 1.7, Rank.NATIONAL to 2.0,
    )

    fun dailyXp(rank: Rank): Int = 150 + 25 * rank.ordinal

    fun daily(ctx: Context): QuestPlan {
        val p = ctx.player
        val b = p.baselines
        val tired = p.fatigue >= 70
        // Struggling last week? Ease off; cruising? Push a little. Either way SafetyLimits has the last word.
        val trend = when {
            ctx.recentCompletion < 0.4 -> 0.85
            ctx.recentCompletion > 0.85 -> 1.08
            else -> 1.0
        }
        val m = (rankMultiplier[p.rank] ?: 1.0) * trend * (if (tired) 0.75 else 1.0)

        val baseSteps = max(max(ctx.avgSteps7d, b.avgSteps), 4000)
        val raw = buildList {
            add(ObjectivePlan(ObjectiveType.STEPS, roundTo((baseSteps * 1.1 * m), 250)))
            add(ObjectivePlan(ObjectiveType.PUSHUPS, roundReps(max(b.pushups, 5) * 2.0 * m)))
            add(ObjectivePlan(ObjectiveType.SITUPS, roundReps(max(b.situps, 8) * 2.0 * m)))
            add(ObjectivePlan(ObjectiveType.SQUATS, roundReps(max(b.squats, 10) * 1.5 * m)))
            if (p.rank.ordinal >= Rank.C.ordinal || p.limitations.any) {
                add(ObjectivePlan(ObjectiveType.PLANK_SEC, roundTo(max(b.plankSec, 30) * 1.5 * m, 15)))
            }
            if (p.rank.ordinal >= Rank.D.ordinal && !tired) {
                add(ObjectivePlan(ObjectiveType.BRISK_MIN, roundTo(20 * m, 5)))
            }
        }
        val title = if (tired) "Daily Quest: Recovery Protocol" else "Daily Quest: Preparing to Become Stronger"
        val flavor = when {
            tired -> "The System has detected accumulated fatigue. Today's load has been reduced. Recovery is also training."
            ctx.recentCompletion > 0.85 -> "Your consistency has been recorded. The System raises the bar."
            else -> "The Daily Quest has arrived. Complete every objective before midnight."
        }
        val plan = QuestPlan(QuestKind.DAILY, title, flavor, raw, dailyXp(p.rank))
        return SafetyLimits.sanitize(plan, p, ctx.previousTargets, flavor)
    }

    /** "Survival": extra walking inside a fixed window. Movement only, capped. */
    fun penalty(p: PlayerEntity, completion: Double): QuestPlan {
        val missing = (1.0 - completion).coerceIn(0.0, 1.0)
        val steps = roundTo((2500 + 500 * p.rank.ordinal + 2500 * missing), 250).coerceAtMost(6000)
        val capped = SafetyLimits.clampTarget(ObjectiveType.STEPS, steps, p, null)
        return QuestPlan(
            kind = QuestKind.PENALTY,
            title = "Penalty Quest: Survival",
            flavor = "You have failed to complete the Daily Quest. You will be transported to the Penalty Zone. " +
                "Survive: walk the distance before the time limit expires.",
            objectives = listOf(ObjectivePlan(ObjectiveType.STEPS, capped)),
            xpReward = 50,
        )
    }

    /** A Rank-Up Trial: a heavier day, all in one go. */
    fun trial(ctx: Context, target: Rank): QuestPlan {
        val daily = daily(ctx.copy(player = ctx.player.copy(fatigue = 0)))
        val objectives = daily.objectives.map { o ->
            val boosted = if (o.type == ObjectiveType.STEPS) roundTo(o.target * 1.3, 250) else roundReps(o.target * 1.3)
            // Trials may exceed the usual week-over-week rise, never the hard cap.
            ObjectivePlan(o.type, boosted.coerceAtMost(SafetyLimits.cap(o.type, ctx.player)))
        }
        return QuestPlan(
            kind = QuestKind.TRIAL,
            title = "Rank-Up Trial: ${target.displayName}",
            flavor = "The Hunter Association requests a re-evaluation. Prove you have outgrown ${ctx.player.rank.displayName}.",
            objectives = objectives,
            xpReward = 600 + 200 * target.ordinal,
        )
    }

    private fun roundTo(v: Double, step: Int): Int = max(step, ((v / step).roundToInt()) * step)

    private fun roundReps(v: Double): Int {
        val r = v.roundToInt()
        return if (r > 20) ((r + 2) / 5) * 5 else max(r, 3)
    }
}
