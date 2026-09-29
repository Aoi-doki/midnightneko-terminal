package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity
import kotlin.math.roundToInt

object PenaltyEngine {
    const val WEAKENED_DAYS = 3
    const val RECOVERY_PER_MONTH = 4
    const val PENALTY_WINDOW_HOURS = 6L
    /** The Penalty Zone never opens before this hour — nobody is made to walk at 3 a.m. */
    const val PENALTY_EARLIEST_HOUR = 7

    data class Outcome(
        val player: PlayerEntity,
        val hpLost: Int,
        val xpLost: Int,
        val died: Boolean,
    )

    /** A Daily Quest settled incomplete. [completion] is 0..1. */
    fun failDaily(p: PlayerEntity, completion: Double): Outcome {
        val missing = (1.0 - completion).coerceIn(0.0, 1.0)
        val maxHp = Progression.maxHp(p)
        val hpLoss = (maxHp * (0.15 + 0.35 * missing)).roundToInt()
        val xpLoss = (Progression.xpToNext(p.level) * 0.25 * missing).roundToInt()
        val (afterXp, lost) = Progression.loseXp(p, xpLoss)
        val hit = afterXp.copy(
            streak = 0,
            questsFailed = p.questsFailed + 1,
        )
        return applyDamage(hit, hpLoss, lost)
    }

    /** The Penalty Quest itself expired. */
    fun failPenalty(p: PlayerEntity, today: Long): Outcome {
        val hpLoss = (Progression.maxHp(p) * 0.20).roundToInt()
        val weakened = p.copy(weakenedUntilDay = today + WEAKENED_DAYS - 1)
        return applyDamage(weakened, hpLoss, 0)
    }

    private fun applyDamage(p: PlayerEntity, hpLoss: Int, xpLost: Int): Outcome {
        val hp = p.hp - hpLoss
        if (hp > 0) return Outcome(p.copy(hp = hp), hpLoss, xpLost, died = false)
        // Death: the Player is reborn one level lower with a fresh start.
        val reborn = p.copy(level = (p.level - 1).coerceAtLeast(1), xp = 0, deaths = p.deaths + 1, streak = 0)
        return Outcome(Progression.fullRestore(reborn), hpLoss, xpLost, died = true)
    }

    fun monthKey(epochDay: Long): Int {
        val d = java.time.LocalDate.ofEpochDay(epochDay)
        return d.year * 100 + d.monthValue
    }

    fun recoveryLeft(p: PlayerEntity, today: Long): Int {
        val used = if (p.recoveryMonth == monthKey(today)) p.recoveryUsed else 0
        return (RECOVERY_PER_MONTH - used).coerceAtLeast(0)
    }

    /** Declare today a Recovery day (sick/rest). Returns null when this month's allowance is spent. */
    fun declareRecovery(p: PlayerEntity, today: Long): PlayerEntity? {
        if (p.recoveryDay == today) return p
        if (recoveryLeft(p, today) <= 0) return null
        val month = monthKey(today)
        val used = if (p.recoveryMonth == month) p.recoveryUsed else 0
        return p.copy(recoveryMonth = month, recoveryUsed = used + 1, recoveryDay = today)
    }
}
