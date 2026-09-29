package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hunter Power: a 0–100 read of the Player's real-world condition, from four equal parts.
 * Norms are age-adjusted so a 50-year-old is measured against a 50-year-old's bar.
 */
object RankEngine {
    data class Power(
        val body: Int,
        val cardio: Int,
        val strength: Int,
        val discipline: Int,
    ) {
        val total: Int get() = ((body + cardio + strength + discipline) / 4.0).roundToInt()
    }

    data class Inputs(
        val player: PlayerEntity,
        val avgSteps7d: Int,
        val completionRate14d: Double,
    )

    /** 1.0 up to age 30, easing down to 0.65 at 70+. */
    fun ageFactor(age: Int): Double = when {
        age <= 30 -> 1.0
        age >= 70 -> 0.65
        else -> 1.0 - (age - 30) * (0.35 / 40.0)
    }

    fun power(i: Inputs): Power {
        val p = i.player
        val af = ageFactor(p.age)

        // Body: distance from the healthy BMI band, plus progress toward the goal weight.
        val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
        val bmiDistance = when {
            bmi <= 0 -> 10.0
            bmi < 18.5 -> 18.5 - bmi
            bmi <= 24.9 -> 0.0
            else -> bmi - 24.9
        }
        // Older bodies carry a little more tolerance before the score starts to drop.
        val tolerance = if (p.age >= 50) 1.5 else 0.0
        val bmiScore = (100 - (bmiDistance - tolerance).coerceAtLeast(0.0) * 7).coerceIn(0.0, 100.0)
        val toLose = p.startWeightKg - p.goalWeightKg
        val goalProgress = if (toLose <= 0.1) 1.0 else ((p.startWeightKg - p.weightKg) / toLose).coerceIn(0.0, 1.0)
        val body = 0.75 * bmiScore + 25 * goalProgress

        // Cardio: 7-day average steps against an age-adjusted curve.
        val cardio = piecewise(
            i.avgSteps7d / af,
            listOf(0.0 to 0.0, 2000.0 to 10.0, 5000.0 to 35.0, 8000.0 to 60.0, 10000.0 to 75.0, 12500.0 to 90.0, 15000.0 to 100.0),
        )

        // Strength: measured reps against age-adjusted norms. Unmeasured parts don't count against you,
        // but with nothing measured the part sits at a neutral low value until the Assessment.
        val b = p.baselines
        val parts = listOfNotNull(
            b.pushups.takeIf { it > 0 }?.let { (it / (40 * af) * 100).coerceAtMost(100.0) },
            b.squats.takeIf { it > 0 }?.let { (it / (60 * af) * 100).coerceAtMost(100.0) },
            b.situps.takeIf { it > 0 }?.let { (it / (45 * af) * 100).coerceAtMost(100.0) },
            b.plankSec.takeIf { it > 0 }?.let { (it / (180 * af) * 100).coerceAtMost(100.0) },
        )
        val strength = if (parts.isEmpty()) 10.0 else parts.average()

        // Discipline: streak (30 days maxes it) and 14-day completion rate.
        val discipline = (p.streak / 30.0).coerceAtMost(1.0) * 50 + i.completionRate14d.coerceIn(0.0, 1.0) * 50

        return Power(body.roundToInt(), cardio.roundToInt(), strength.roundToInt(), discipline.roundToInt())
    }

    /** The highest rank this power and level qualify for (the Player still has to pass the trial). */
    fun qualifiedRank(power: Int, level: Int): Rank =
        Rank.entries.last { power >= it.minPower && level >= it.minLevel }

    /** The next rank the Player can take a trial for right now, or null. */
    fun trialAvailable(p: PlayerEntity, power: Int, today: Long): Rank? {
        if (p.trialCooldownUntilDay >= today) return null
        val next = p.rank.next ?: return null
        return next.takeIf { qualifiedRank(power, p.level).ordinal >= it.ordinal }
    }

    private fun piecewise(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x <= points.first().first) return points.first().second
        for (k in 1 until points.size) {
            val (x1, y1) = points[k]
            val (x0, y0) = points[k - 1]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return points.last().second
    }

    /** Used in tests and the Rank screen: how far off the next rank's power bar the Player is. */
    fun gapToNext(p: PlayerEntity, power: Int): Int? = p.rank.next?.let { abs((it.minPower - power).coerceAtLeast(0)) }
}
