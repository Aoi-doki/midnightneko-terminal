package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.PlayerEntity
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Levels, XP, HP/MP and ability points.
 *
 * The XP curve is linear in the level (`80 + 70·level` to reach the next one) rather than the
 * steep power curves games use. Real bodies change slowly, so the curve is tuned to real time:
 * completing every daily quest reaches level ~12 in a month, ~30 in six months and 50 in a year.
 */
object Progression {
    const val POINTS_PER_LEVEL = 3
    const val MAX_LEVEL = 999

    fun xpToNext(level: Int): Int = 80 + 70 * level

    fun maxHp(p: PlayerEntity): Int = 100 + p.vit * 10 + (p.level - 1) * 5
    fun maxMp(p: PlayerEntity): Int = 30 + p.intel * 8 + (p.level - 1) * 2

    fun stat(p: PlayerEntity, s: StatType): Int = when (s) {
        StatType.STR -> p.str
        StatType.AGI -> p.agi
        StatType.VIT -> p.vit
        StatType.SEN -> p.sen
        StatType.INT -> p.intel
    }

    fun withStat(p: PlayerEntity, s: StatType, value: Int): PlayerEntity = when (s) {
        StatType.STR -> p.copy(str = value)
        StatType.AGI -> p.copy(agi = value)
        StatType.VIT -> p.copy(vit = value)
        StatType.SEN -> p.copy(sen = value)
        StatType.INT -> p.copy(intel = value)
    }

    data class XpResult(val player: PlayerEntity, val gained: Int, val levelsGained: Int)

    fun isWeakened(p: PlayerEntity, today: Long): Boolean = p.weakenedUntilDay >= today

    /** Adds XP (after the Weakened debuff, if active), levelling up as many times as it covers. */
    fun addXp(p: PlayerEntity, amount: Int, today: Long): XpResult {
        val effective = if (isWeakened(p, today)) (amount * 0.75).roundToInt() else amount
        var level = p.level
        var xp = p.xp + max(0, effective)
        var gained = 0
        while (level < MAX_LEVEL && xp >= xpToNext(level)) {
            xp -= xpToNext(level)
            level++
            gained++
        }
        var next = p.copy(level = level, xp = xp, freePoints = p.freePoints + gained * POINTS_PER_LEVEL)
        if (gained > 0) {
            // As in the show, a level-up fully restores the Player.
            next = next.copy(hp = maxHp(next), mp = maxMp(next))
        }
        return XpResult(next, effective, gained)
    }

    /** Takes XP away, but never below the start of the current level: penalties sting, they don't un-level. */
    fun loseXp(p: PlayerEntity, amount: Int): Pair<PlayerEntity, Int> {
        val lost = amount.coerceIn(0, p.xp)
        return p.copy(xp = p.xp - lost) to lost
    }

    fun allocate(p: PlayerEntity, s: StatType): PlayerEntity? {
        if (p.freePoints <= 0) return null
        val raised = withStat(p, s, stat(p, s) + 1).copy(freePoints = p.freePoints - 1)
        // Raising VIT/INT raises the max; keep the current value's gap the same so it reads as growth.
        return raised.copy(
            hp = (p.hp + (maxHp(raised) - maxHp(p))).coerceAtMost(maxHp(raised)),
            mp = (p.mp + (maxMp(raised) - maxMp(p))).coerceAtMost(maxMp(raised)),
        )
    }

    fun heal(p: PlayerEntity, hpFraction: Double, mpFraction: Double = 0.0): PlayerEntity = p.copy(
        hp = (p.hp + (maxHp(p) * hpFraction).roundToInt()).coerceIn(0, maxHp(p)),
        mp = (p.mp + (maxMp(p) * mpFraction).roundToInt()).coerceIn(0, maxMp(p)),
    )

    fun fullRestore(p: PlayerEntity): PlayerEntity = p.copy(hp = maxHp(p), mp = maxMp(p))

    /**
     * Starting stats from measured (or self-reported) numbers. Everyone starts near 10 — the
     * point is where you go from here — but a strong start shows.
     */
    fun initialStats(p: PlayerEntity): PlayerEntity {
        val b = p.baselines
        val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
        val str = 8 + b.pushups / 4 + b.squats / 10
        val agi = 8 + b.avgSteps / 2000
        val vit = 8 + b.plankSec / 20 + b.situps / 10 - ((bmi - 25).coerceAtLeast(0.0) / 3).roundToInt()
        val sen = 10
        val intel = 10
        val seeded = p.copy(
            str = str.coerceIn(5, 40),
            agi = agi.coerceIn(5, 40),
            vit = vit.coerceIn(5, 40),
            sen = sen,
            intel = intel,
        )
        return fullRestore(seeded)
    }
}
