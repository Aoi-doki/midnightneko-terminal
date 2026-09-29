package dev.aoidoki.arise.ai

import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.engine.BodyMetrics
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestPlan
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.engine.RankEngine
import dev.aoidoki.arise.engine.SafetyLimits

/**
 * Prompts for a ~1B instruction model (Qwen2.5 chat template). Short, concrete, one JSON example,
 * the Player's own words passed through verbatim. The rails are stated in the prompt *and*
 * enforced afterwards in code — the prompt is a request, [SafetyLimits] is the law.
 */
object Prompts {
    private const val SYSTEM = "You are the System from Solo Leveling: a cold, precise, slightly ominous interface that " +
        "trains one Player to become stronger and lose weight safely. You speak in short, formal sentences. " +
        "You NEVER suggest fasting, skipping meals, eating less, calorie limits, dehydration or anything about food " +
        "restriction. Penalties and training are always movement. You respect every injury or condition the Player mentions. " +
        "Reply with ONE JSON object and nothing else."

    fun chat(user: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private fun body(p: PlayerEntity): String {
        val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
        return "Age ${p.age}. Height ${p.heightCm.toInt()} cm. Weight ${"%.1f".format(p.weightKg)} kg " +
            "(start ${"%.1f".format(p.startWeightKg)}, goal ${"%.1f".format(p.goalWeightKg)}). BMI ${"%.1f".format(bmi)} (${BodyMetrics.bmiClass(bmi)})."
    }

    /** [compact] for small-context models (Lite: 1,280 tokens for prompt + answer). */
    private fun words(p: PlayerEntity, stats: List<CustomStatEntity>, compact: Boolean): String = buildString {
        val aboutMax = if (compact) 350 else 1200
        val statsMax = if (compact) 10 else 30
        if (p.about.isNotBlank()) append("In their own words: \"${p.about.take(aboutMax)}\"\n")
        if (stats.isNotEmpty()) {
            append("Their self-defined stats:\n")
            stats.take(statsMax).forEach { append("- ${it.name.take(if (compact) 30 else 60)}: ${it.value.take(if (compact) 50 else 120)}\n") }
        }
    }

    fun assessment(p: PlayerEntity, stats: List<CustomStatEntity>, compact: Boolean = false): String = chat(
        """
        Assess this new Player.
        ${body(p)}
        ${words(p, stats, compact)}
        Give starting stats between 5 and 30 (10 is an average untrained adult): str (strength), agi (agility), vit (vitality/endurance), sen (senses/reflexes), int (intelligence/discipline).
        Mark care flags for any injury or condition they mention. Estimate a baseline only when their words support it, otherwise 0.
        Write a 2-3 sentence System evaluation of the Player in "summary".
        JSON format:
        {"summary":"Player evaluated. ...","stats":{"str":10,"agi":10,"vit":9,"sen":11,"int":12},"care":{"knee":false,"back":false,"upper_body":false,"cardio":false,"no_jumping":false},"notes":["..."],"baseline":{"pushups":0,"squats":0,"situps":0,"plank_sec":0,"steps":0}}
        """.trimIndent(),
    )

    fun dailyQuest(p: PlayerEntity, stats: List<CustomStatEntity>, rules: QuestPlan, history: String, avoid: QuestPlan? = null, compact: Boolean = false): String {
        val caps = rules.objectives.joinToString(", ") { "${key(it.type)} ≤ ${SafetyLimits.cap(it.type, p)}" }
        val allowed = ObjectiveType.entries.filter { SafetyLimits.allowed(it, p) }.joinToString(", ") { key(it) }
        val suggested = rules.objectives.joinToString(", ") { "${key(it.type)} ${it.target}" }
        return chat(
            """
            Write today's Daily Quest for this Player.
            ${body(p)} Rank ${p.rank.displayName}, level ${p.level}. Fatigue ${p.fatigue}/100. Streak ${p.streak} days.
            Measured: push-ups ${p.baselines.pushups}, squats ${p.baselines.squats}, sit-ups ${p.baselines.situps}, plank ${p.baselines.plankSec}s, avg steps ${p.baselines.avgSteps}.
            ${words(p, stats, compact)}
            Last days: $history
            ${if (p.limitations.notes.isNotEmpty()) "Constraints: ${p.limitations.notes.joinToString(" ")}" else ""}
            Allowed objective types: $allowed.
            Hard limits: $caps.
            A balanced suggestion is: $suggested. Adjust it to fit the Player's own words and recent days. 3 to 5 objectives.
            ${if (avoid != null) "Make it clearly different from: ${avoid.objectives.joinToString(", ") { key(it.type) }}." else ""}
            "flavor" is one or two sentences the System says when the quest arrives, addressing the Player.
            JSON format:
            {"title":"Daily Quest: Preparing to Become Stronger","flavor":"...","objectives":[{"type":"steps","target":8000},{"type":"pushups","target":30}]}
            """.trimIndent(),
        )
    }

    fun trial(p: PlayerEntity, stats: List<CustomStatEntity>, rules: QuestPlan, target: Rank, compact: Boolean = false): String = chat(
        """
        The Player qualifies for promotion from ${p.rank.displayName} to ${target.displayName}. Design the one-day Rank-Up Trial.
        ${body(p)} Level ${p.level}.
        ${words(p, stats, compact)}
        It must be harder than a normal day but achievable in one day. A baseline trial is: ${rules.objectives.joinToString(", ") { "${key(it.type)} ${it.target}" }}.
        Keep the same objective types, adjust targets within ±15%, write a dramatic title and flavor.
        JSON format:
        {"title":"Rank-Up Trial: ...","flavor":"...","objectives":[{"type":"steps","target":12000}]}
        """.trimIndent(),
    )

    fun evaluation(p: PlayerEntity, power: RankEngine.Power): String = chat(
        """
        Evaluate the Player's Hunter Power in 2-3 cold, specific sentences, and name the single weakest area to train next.
        ${body(p)} Rank ${p.rank.displayName}, level ${p.level}, streak ${p.streak}.
        Hunter Power ${power.total}/100 — body ${power.body}, cardio ${power.cardio}, strength ${power.strength}, discipline ${power.discipline}.
        JSON format: {"text":"..."}
        """.trimIndent(),
    )

    fun key(t: ObjectiveType): String = when (t) {
        ObjectiveType.STEPS -> "steps"
        ObjectiveType.DISTANCE_M -> "distance_m"
        ObjectiveType.BRISK_MIN -> "brisk_min"
        ObjectiveType.PUSHUPS -> "pushups"
        ObjectiveType.SQUATS -> "squats"
        ObjectiveType.SITUPS -> "situps"
        ObjectiveType.PLANK_SEC -> "plank_sec"
        ObjectiveType.SLEEP_MIN -> "sleep_min"
        ObjectiveType.MEDITATE_MIN -> "meditate_min"
    }
}
