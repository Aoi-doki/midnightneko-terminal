package dev.aoidoki.arise.engine

import kotlin.math.roundToInt

object BodyMetrics {
    fun bmi(weightKg: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        return if (m <= 0) 0.0 else weightKg / (m * m)
    }

    fun bmiClass(bmi: Double): String = when {
        bmi <= 0 -> "Unknown"
        bmi < 18.5 -> "Underweight"
        bmi < 25 -> "Healthy"
        bmi < 30 -> "Overweight"
        bmi < 35 -> "Obese I"
        bmi < 40 -> "Obese II"
        else -> "Obese III"
    }

    /** Walking stride estimate from height (the standard 0.415 × height rule). */
    fun strideM(heightCm: Double): Double = heightCm * 0.415 / 100.0

    fun stepsToMeters(steps: Int, heightCm: Double): Int = (steps * strideM(heightCm)).roundToInt()

    /** Walking burn: roughly 0.5 kcal per kg per km. */
    fun walkKcal(steps: Int, weightKg: Double, heightCm: Double): Int =
        (stepsToMeters(steps, heightCm) / 1000.0 * weightKg * 0.5).roundToInt()

    /** Mifflin–St Jeor. Without a sex hint, the average of both equations. */
    fun bmr(weightKg: Double, heightCm: Double, age: Int, sexHint: String): Int {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * age
        return when (sexHint) {
            "m" -> base + 5
            "f" -> base - 161
            else -> base - 78
        }.roundToInt()
    }

    /** The lightest goal the System will accept: BMI 18.5. */
    fun minHealthyWeight(heightCm: Double): Double {
        val m = heightCm / 100.0
        return 18.5 * m * m
    }

    /** Safe loss pace is ~0.5–1% of body weight per week. */
    fun safeWeeklyLossKg(weightKg: Double): ClosedFloatingPointRange<Double> = (weightKg * 0.005)..(weightKg * 0.01)

    fun weeksToGoal(weightKg: Double, goalKg: Double): Int {
        val perWeek = weightKg * 0.0075
        return if (goalKg >= weightKg || perWeek <= 0) 0 else ((weightKg - goalKg) / perWeek).roundToInt()
    }
}
