package dev.aoidoki.arise.engine

import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.data.PlayerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {
    @Test
    fun `xp curve is linear and positive`() {
        assertEquals(150, Progression.xpToNext(1))
        assertEquals(780, Progression.xpToNext(10))
        // A year of daily quests (~250 XP/day) should land near level 50, not level 500.
        var total = 0
        for (l in 1 until 50) total += Progression.xpToNext(l)
        assertTrue(total in 80_000..100_000)
    }

    @Test
    fun `adding xp levels up, grants points and fully restores`() {
        val p = PlayerEntity(level = 1, xp = 100, hp = 10, mp = 1)
        val r = Progression.addXp(p, 400, today = 0)
        assertEquals(2, r.levelsGained) // 100+400 = 500 → L1 costs 150, L2 costs 220 → level 3 with 130 left
        assertEquals(3, r.player.level)
        assertEquals(130, r.player.xp)
        assertEquals(6, r.player.freePoints)
        assertEquals(Progression.maxHp(r.player), r.player.hp)
    }

    @Test
    fun `weakened players earn a quarter less`() {
        val p = PlayerEntity(weakenedUntilDay = 10)
        assertEquals(75, Progression.addXp(p, 100, today = 10).gained)
        assertEquals(100, Progression.addXp(p, 100, today = 11).gained)
    }

    @Test
    fun `losing xp never drops a level`() {
        val p = PlayerEntity(level = 5, xp = 40)
        val (after, lost) = Progression.loseXp(p, 1000)
        assertEquals(5, after.level)
        assertEquals(0, after.xp)
        assertEquals(40, lost)
    }

    @Test
    fun `allocating spends a point and raises max hp for vit`() {
        val p = Progression.fullRestore(PlayerEntity(freePoints = 1, vit = 10))
        val q = Progression.allocate(p, StatType.VIT)!!
        assertEquals(11, q.vit)
        assertEquals(0, q.freePoints)
        assertEquals(Progression.maxHp(q), q.hp)
        assertNull(Progression.allocate(q, StatType.STR))
    }
}

class RankEngineTest {
    private fun player(age: Int = 30, weight: Double = 70.0, streak: Int = 0, b: Baselines = Baselines(), level: Int = 1, rank: Rank = Rank.E) =
        PlayerEntity(age = age, heightCm = 175.0, weightKg = weight, startWeightKg = weight, goalWeightKg = weight, streak = streak, baselines = b, level = level, rank = rank)

    @Test
    fun `untrained sedentary player is E rank power`() {
        val power = RankEngine.power(RankEngine.Inputs(player(weight = 110.0), avgSteps7d = 2500, completionRate14d = 0.0))
        assertTrue("power ${power.total}", power.total < Rank.D.minPower)
    }

    @Test
    fun `fit consistent player clears S rank power`() {
        val b = Baselines(pushups = 45, squats = 65, situps = 50, plankSec = 200, avgSteps = 14000)
        val power = RankEngine.power(RankEngine.Inputs(player(streak = 40, b = b), avgSteps7d = 15000, completionRate14d = 1.0))
        assertTrue("power ${power.total}", power.total >= Rank.S.minPower)
    }

    @Test
    fun `older players are measured against their own norms`() {
        val b = Baselines(pushups = 20, squats = 30)
        val young = RankEngine.power(RankEngine.Inputs(player(age = 25, b = b), 6000, 0.5))
        val old = RankEngine.power(RankEngine.Inputs(player(age = 65, b = b), 6000, 0.5))
        assertTrue(old.strength > young.strength)
        assertTrue(old.cardio > young.cardio)
    }

    @Test
    fun `rank needs power and level`() {
        assertEquals(Rank.E, RankEngine.qualifiedRank(power = 90, level = 1))
        assertEquals(Rank.C, RankEngine.qualifiedRank(power = 40, level = 30))
        assertEquals(Rank.S, RankEngine.qualifiedRank(power = 85, level = 45))
        assertEquals(Rank.NATIONAL, RankEngine.qualifiedRank(power = 95, level = 60))
    }

    @Test
    fun `trial offers only the next rank and respects cooldown`() {
        val p = player(level = 20, rank = Rank.D)
        assertEquals(Rank.C, RankEngine.trialAvailable(p, power = 60, today = 5))
        assertNull(RankEngine.trialAvailable(p.copy(trialCooldownUntilDay = 6), power = 60, today = 5))
        assertNull(RankEngine.trialAvailable(p, power = 10, today = 5))
    }
}

class SafetyLimitsTest {
    private val p = PlayerEntity(age = 30, weightKg = 90.0, heightCm = 178.0, baselines = Baselines(pushups = 10, squats = 20, situps = 15, plankSec = 40, avgSteps = 5000))

    @Test
    fun `hostile ai plan is clamped and cleaned`() {
        val hostile = QuestPlan(
            QuestKind.DAILY,
            title = "Skip meals today",
            flavor = "Fasting builds power. Eat under 800 calories.",
            objectives = listOf(
                ObjectivePlan(ObjectiveType.PUSHUPS, 500),
                ObjectivePlan(ObjectiveType.STEPS, 90000),
                ObjectivePlan(ObjectiveType.PUSHUPS, 20),
            ),
            xpReward = 99999,
        )
        val safe = SafetyLimits.sanitize(hostile, p, emptyMap(), "fallback")
        assertEquals("Daily Quest", safe.title)
        assertEquals("fallback", safe.flavor)
        assertEquals(2, safe.objectives.size) // duplicate push-ups dropped
        assertEquals(50, safe.objectives.first { it.type == ObjectiveType.PUSHUPS }.target) // 10 × 5
        assertTrue(safe.objectives.first { it.type == ObjectiveType.STEPS }.target <= 25000)
        assertEquals(2000, safe.xpReward)
    }

    @Test
    fun `food restriction is never safe text`() {
        listOf("skip breakfast", "don't eat after 6", "try a water fast", "eat less than 1200", "1000 kcal only", "use a sauna suit", "take a laxative")
            .forEach { assertFalse(it, SafetyLimits.isSafeText(it)) }
        assertTrue(SafetyLimits.isSafeText("Walk 8,000 steps before midnight."))
        assertTrue(SafetyLimits.isSafeText("Fast feet: brisk walk for 20 minutes."))
    }

    @Test
    fun `targets rise at most about ten percent a week`() {
        assertEquals(22, SafetyLimits.clampTarget(ObjectiveType.PUSHUPS, 40, p, previous = 20))
        assertEquals(5500, SafetyLimits.clampTarget(ObjectiveType.STEPS, 9000, p, previous = 5000))
    }

    @Test
    fun `injuries swap objectives out`() {
        val back = p.copy(limitations = Limitations(backCare = true))
        assertEquals(ObjectiveType.BRISK_MIN, SafetyLimits.substitute(ObjectiveType.SITUPS, back))
        val shoulder = p.copy(limitations = Limitations(upperBodyCare = true))
        assertEquals(ObjectiveType.PLANK_SEC, SafetyLimits.substitute(ObjectiveType.PUSHUPS, shoulder))
        val knee = p.copy(limitations = Limitations(kneeCare = true))
        assertEquals(SafetyLimits.cap(ObjectiveType.SQUATS, p) / 2, SafetyLimits.cap(ObjectiveType.SQUATS, knee))
    }

    @Test
    fun `goal weight is never below a healthy bmi`() {
        val g = SafetyLimits.safeGoalWeight(40.0, 180.0)
        assertEquals(18.5, BodyMetrics.bmi(g, 180.0), 0.01)
    }
}

class PenaltyEngineTest {
    @Test
    fun `failing hurts in proportion to what was left`() {
        val p = Progression.fullRestore(PlayerEntity(level = 4, xp = 200, streak = 9))
        val half = PenaltyEngine.failDaily(p, 0.5)
        val none = PenaltyEngine.failDaily(p, 0.0)
        assertTrue(none.hpLost > half.hpLost)
        assertTrue(none.xpLost > half.xpLost)
        assertEquals(0, half.player.streak)
        assertFalse(half.died)
    }

    @Test
    fun `hp zero means rebirth one level lower`() {
        val p = PlayerEntity(level = 6, xp = 100, hp = 5, streak = 3)
        val o = PenaltyEngine.failDaily(p, 0.0)
        assertTrue(o.died)
        assertEquals(5, o.player.level)
        assertEquals(0, o.player.xp)
        assertEquals(Progression.maxHp(o.player), o.player.hp)
        assertEquals(1, o.player.deaths)
    }

    @Test
    fun `failed penalty quest applies weakened for three days`() {
        val o = PenaltyEngine.failPenalty(Progression.fullRestore(PlayerEntity()), today = 100)
        assertEquals(102, o.player.weakenedUntilDay)
    }

    @Test
    fun `four recovery days a month`() {
        val day = java.time.LocalDate.of(2026, 9, 1).toEpochDay()
        var p = PlayerEntity()
        repeat(4) { i -> p = PenaltyEngine.declareRecovery(p, day + i)!! }
        assertNull(PenaltyEngine.declareRecovery(p, day + 4))
        assertNotNull(PenaltyEngine.declareRecovery(p, java.time.LocalDate.of(2026, 10, 1).toEpochDay()))
    }
}

class ProfileParserTest {
    @Test
    fun `reads self-defined stats in the player's own words`() {
        val stats = listOf(
            CustomStatEntity(name = "max push ups", value = "22"),
            CustomStatEntity(name = "plank", value = "1:30"),
            CustomStatEntity(name = "daily steps", value = "about 4,500"),
            CustomStatEntity(name = "Squats in 2 min", value = "35"),
            CustomStatEntity(name = "left knee", value = "old ACL tear"),
        )
        val r = ProfileParser.read("I'm a guy with a desk job. I read a lot.", stats)
        assertEquals(22, r.baselines.pushups)
        assertEquals(90, r.baselines.plankSec)
        assertEquals(35, r.baselines.squats)
        assertEquals(4500, r.baselines.avgSteps)
        assertTrue(r.limitations.kneeCare)
        assertTrue(r.limitations.noJumping)
        assertEquals("m", r.sexHint)
        assertTrue((r.statBonus[StatType.INT] ?: 0) > 0)
        assertTrue((r.statBonus[StatType.VIT] ?: 0) < 0)
    }

    @Test
    fun `lifting weights is not a squat count`() {
        val r = ProfileParser.read("", listOf(CustomStatEntity(name = "squat", value = "100 kg")))
        assertEquals(0, r.baselines.squats)
    }

    @Test
    fun `cardiac conditions cap cardio`() {
        val r = ProfileParser.read("I have high blood pressure and asthma", emptyList())
        assertTrue(r.limitations.cardioCare)
        val p = PlayerEntity(limitations = r.limitations)
        assertTrue(SafetyLimits.cap(ObjectiveType.STEPS, p) <= 8000)
        assertEquals(30, SafetyLimits.cap(ObjectiveType.BRISK_MIN, p))
    }
}

class QuestPlannerTest {
    private fun ctx(p: PlayerEntity, completion: Double = 0.6) = QuestPlanner.Context(p, 5000, emptyMap(), completion, 1)

    @Test
    fun `daily quest has the four classics for a healthy beginner`() {
        val p = PlayerEntity(baselines = Baselines(pushups = 10, squats = 20, situps = 12, avgSteps = 5000))
        val plan = QuestPlanner.daily(ctx(p))
        val types = plan.objectives.map { it.type }.toSet()
        assertTrue(types.containsAll(listOf(ObjectiveType.STEPS, ObjectiveType.PUSHUPS, ObjectiveType.SITUPS, ObjectiveType.SQUATS)))
        plan.objectives.forEach { assertTrue(it.target <= SafetyLimits.cap(it.type, p)) }
    }

    @Test
    fun `fatigue lowers the load`() {
        val p = PlayerEntity(baselines = Baselines(pushups = 20, squats = 30, situps = 20, avgSteps = 8000))
        val fresh = QuestPlanner.daily(ctx(p)).objectives.first { it.type == ObjectiveType.PUSHUPS }.target
        val tired = QuestPlanner.daily(ctx(p.copy(fatigue = 80))).objectives.first { it.type == ObjectiveType.PUSHUPS }.target
        assertTrue(tired < fresh)
    }

    @Test
    fun `penalty is walking, capped`() {
        val plan = QuestPlanner.penalty(PlayerEntity(rank = Rank.S), 0.0)
        assertEquals(listOf(ObjectiveType.STEPS), plan.objectives.map { it.type })
        assertTrue(plan.objectives.single().target <= 6000)
    }
}
