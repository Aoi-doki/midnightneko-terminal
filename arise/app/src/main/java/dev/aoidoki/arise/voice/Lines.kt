package dev.aoidoki.arise.voice

/**
 * The fixed lines the System speaks. They are rendered and cached in the background at start-up
 * ([SystemVoice.prewarm]) so they play the instant their popup appears instead of waiting on TTS.
 * Keep these in step with the `speech` strings in engine/Game.kt, work/Work.kt and the Train screen.
 */
object Lines {
    private val questFlavors = listOf(
        "The Daily Quest has arrived. Complete every objective before midnight.",
        "The System has detected accumulated fatigue. Today's load has been reduced. Recovery is also training.",
        "Your consistency has been recorded. The System raises the bar.",
    )

    val all: List<String> = buildList {
        // Most frequent first: the prewarm works down this list.
        questFlavors.forEach { add("The daily quest has arrived. $it") }
        listOf(25, 50, 75).forEach { add("Daily quest, $it percent complete.") }
        add("You have completed the daily quest. Rewards have been distributed.")
        add("You have leveled up.")
        add("Warning. Failure to complete the daily quest will result in an appropriate penalty.")
        add("Daily quest incomplete. A penalty will be applied.")
        add("You have failed to complete the daily quest. You will now be transported to the penalty zone.")
        add("The penalty zone is now open. Survive.")
        add("You have survived the penalty zone.")
        add("You have failed to survive. A debuff has been applied. Weakened.")
        add("You have died. Rise again.")
        add("Good morning, Player. The daily quest awaits.")
        add("Begin.")
        add("Set recorded.")
        add("Time.")
        (10..100 step 10).forEach { add("$it") }
        add("New record.")
        add("Assessment complete. Your data has been recorded.")
        add("Recovery status registered. Rest well, Player.")
        add("The trial has begun. Prove yourself.")
        add("The trial has ended in failure. You are not yet ready.")
        add("The quest has been rewritten at the cost of mana.")
        add("You were gone. The System noticed.")
    }
}

/**
 * Generation counter that lets [SystemVoice.stop] invalidate everything already queued: a line
 * only plays if the generation it was queued under is still current.
 */
class SpeechGate {
    @Volatile
    var generation: Int = 0
        private set

    fun current(): Int = generation

    fun invalidate(): Int {
        generation++
        return generation
    }

    fun isStale(queuedAt: Int): Boolean = queuedAt != generation
}
