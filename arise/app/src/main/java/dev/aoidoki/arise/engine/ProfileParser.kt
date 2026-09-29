package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.CustomStatEntity
import kotlin.math.roundToInt

/**
 * Reads the Player's own words — free text and self-named stats — without a model.
 *
 * This is the floor, not the ceiling: when the on-device AI is installed it reads the same words
 * and does better. But the app must understand "max pushups: 22", "5k in 34 min" or "my left knee
 * is bad" even with no model at all.
 */
object ProfileParser {

    data class Reading(
        val baselines: Baselines,
        val limitations: Limitations,
        val sexHint: String,
        /** Stat nudges (−3..+5) for things like "I lift", "I read a lot", "gamer, fast reactions". */
        val statBonus: Map<StatType, Int>,
    )

    private fun firstNumber(s: String): Double? {
        // "4,500" and "12,000" are thousands; "1,5" and "2.5" are decimals.
        Regex("\\b\\d{1,3}(?:,\\d{3})+\\b").find(s)?.let { return it.value.replace(",", "").toDoubleOrNull() }
        return Regex("(\\d+(?:[.,]\\d+)?)").find(s)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun secondsIn(s: String): Int? {
        Regex("(\\d+)\\s*:\\s*(\\d{2})").find(s)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(m|min|mins|minutes?)\\b", RegexOption.IGNORE_CASE).find(s)?.let {
            val mins = it.groupValues[1].toDouble()
            val secs = Regex("(\\d+)\\s*(s|sec|secs|seconds?)\\b", RegexOption.IGNORE_CASE).find(s.substring(it.range.last + 1))
                ?.groupValues?.get(1)?.toInt() ?: 0
            return (mins * 60).roundToInt() + secs
        }
        Regex("(\\d+)\\s*(s|sec|secs|seconds?)\\b", RegexOption.IGNORE_CASE).find(s)?.let { return it.groupValues[1].toInt() }
        return null
    }

    private val pushRe = Regex("push[\\s-]?ups?|pushups?|press[\\s-]?ups?", RegexOption.IGNORE_CASE)
    private val squatRe = Regex("squats?", RegexOption.IGNORE_CASE)
    private val situpRe = Regex("sit[\\s-]?ups?|situps?|crunch(es)?", RegexOption.IGNORE_CASE)
    private val plankRe = Regex("plank", RegexOption.IGNORE_CASE)
    private val stepsRe = Regex("steps?", RegexOption.IGNORE_CASE)

    fun read(about: String, stats: List<CustomStatEntity>): Reading {
        var pushups = 0
        var squats = 0
        var situps = 0
        var plank = 0
        var steps = 0
        // For a self-defined stat the number lives in its value ("Squats in 2 min" → "35"), for prose anywhere.
        val lines = stats.map { "${it.name}: ${it.value}" to (it.value.takeIf { v -> firstNumber(v) != null } ?: it.name) } +
            about.split(Regex("[\\n;]+|\\.(?!\\d)")).map { it to it }

        for ((line, numberSource) in lines) {
            val n = firstNumber(numberSource)?.roundToInt() ?: continue
            when {
                pushRe.containsMatchIn(line) -> pushups = maxOf(pushups, n.coerceAtMost(200))
                situpRe.containsMatchIn(line) -> situps = maxOf(situps, n.coerceAtMost(200))
                squatRe.containsMatchIn(line) && !Regex("kg|lb", RegexOption.IGNORE_CASE).containsMatchIn(line) ->
                    squats = maxOf(squats, n.coerceAtMost(300))
                plankRe.containsMatchIn(line) -> plank = maxOf(plank, (secondsIn(numberSource) ?: n).coerceAtMost(1200))
                stepsRe.containsMatchIn(line) && n >= 500 -> steps = maxOf(steps, n.coerceAtMost(40000))
            }
            // "5k in 34 min" / "run 3 km a day": a runner walks plenty.
            if (steps == 0 && Regex("(\\d+(?:\\.\\d+)?)\\s*(k|km)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                Regex("run|jog|walk", RegexOption.IGNORE_CASE).containsMatchIn(line)
            ) {
                steps = 7000
            }
        }

        val all = lines.joinToString(" ") { it.first }.lowercase()
        fun has(vararg words: String) = words.any { all.contains(it) }

        val knee = has("knee", "acl", "meniscus")
        val back = has("back pain", "bad back", "lower back", "herniat", "disc", "spine", "sciatica")
        val upper = has("wrist", "shoulder", "rotator", "elbow", "tennis elbow")
        val cardio = has("heart", "cardiac", "arrhythm", "pregnan", "blood pressure", "hypertension", "asthma", "copd")
        val noJump = knee || has("ankle", "shin splint", "joint pain")

        val notes = buildList {
            if (knee) add("Knee care: squat volume halved, no jumping.")
            if (back) add("Back care: no sit-ups.")
            if (upper) add("Upper-body care: no push-ups, short planks.")
            if (cardio) add("Cardio care: steps capped at 8,000, brisk work capped at 30 min. Check with a doctor.")
            if (noJump && !knee) add("Joint care: no jumping.")
        }

        val sex = when {
            Regex("\\b(female|woman|girl|she/her|mom|mother|wife)\\b").containsMatchIn(all) -> "f"
            Regex("\\b(male|man|guy|boy|he/him|dad|father|husband)\\b").containsMatchIn(all) -> "m"
            else -> ""
        }

        val bonus = mutableMapOf<StatType, Int>()
        if (has("lift", "gym", "deadlift", "bench", "weights", "calisthenics")) bonus[StatType.STR] = 3
        if (has("run", "sprint", "football", "soccer", "basketball", "martial", "boxing", "mma")) bonus[StatType.AGI] = 3
        if (has("hike", "swim", "cycl", "bike", "manual labor", "construction", "warehouse")) bonus[StatType.VIT] = 2
        if (has("gamer", "gaming", "reflex", "hunter", "archery", "shooting", "driver")) bonus[StatType.SEN] = 2
        if (has("read", "study", "student", "engineer", "developer", "programmer", "chess", "doctor", "nurse")) bonus[StatType.INT] = 3
        if (has("sedentary", "desk job", "couch", "never exercise", "out of shape")) bonus[StatType.VIT] = (bonus[StatType.VIT] ?: 0) - 2

        return Reading(
            baselines = Baselines(pushups, squats, situps, plank, steps),
            limitations = Limitations(noJump, knee, back, upper, cardio, notes),
            sexHint = sex,
            statBonus = bonus,
        )
    }

    /** One-paragraph System read of the Player when no model is available. */
    fun summary(name: String, bmi: Double, r: Reading, age: Int): String {
        val body = BodyMetrics.bmiClass(bmi).lowercase()
        val b = r.baselines
        val measured = listOfNotNull(
            b.pushups.takeIf { it > 0 }?.let { "$it push-ups" },
            b.squats.takeIf { it > 0 }?.let { "$it squats" },
            b.situps.takeIf { it > 0 }?.let { "$it sit-ups" },
            b.plankSec.takeIf { it > 0 }?.let { "a ${it}s plank" },
            b.avgSteps.takeIf { it > 0 }?.let { "%,d daily steps".format(it) },
        )
        val cap = if (measured.isEmpty()) "No combat data recorded. The Assessment will measure you."
        else "Reported capability: ${measured.joinToString(", ")}."
        val lim = if (r.limitations.notes.isEmpty()) "" else " Constraints registered: ${r.limitations.notes.joinToString(" ")}"
        return "Player $name, age $age. Body classification: $body (BMI %.1f). $cap$lim".format(bmi)
    }
}
