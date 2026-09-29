package dev.aoidoki.arise.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.aoidoki.arise.core.TimeSource
import dev.aoidoki.arise.data.AriseDatabase
import dev.aoidoki.arise.data.CustomStatEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test drives by hand, in UTC. */
class FakeTime(start: LocalDateTime) : TimeSource() {
    var nowMillis: Long = start.toInstant(ZoneOffset.UTC).toEpochMilli()
    override fun now(): Long = nowMillis + offsetMillis
    override fun zone(): ZoneId = ZoneOffset.UTC
    fun plusHours(h: Long) { nowMillis += h * 3_600_000 }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GameTest {
    private lateinit var db: AriseDatabase
    private lateinit var time: FakeTime
    private lateinit var game: Game

    @Before
    fun setUp() {
        db = AriseDatabase.inMemory(ApplicationProvider.getApplicationContext())
        time = FakeTime(LocalDateTime.of(2026, 9, 28, 9, 0))
        game = Game(db, time)
    }

    @After
    fun tearDown() = db.close()

    private fun awaken() = runBlocking {
        game.awaken(
            Game.Profile(
                name = "Jinwoo", age = 24, heightCm = 180.0, weightKg = 92.0, goalWeightKg = 80.0,
                about = "Out of shape guy, desk job.",
                customStats = listOf(CustomStatEntity(name = "max pushups", value = "10"), CustomStatEntity(name = "squats", value = "20")),
            ),
        )
    }

    private fun completeToday() = runBlocking {
        val q = game.todayQuest()!!
        for (o in q.objectives) {
            when (o.type) {
                ObjectiveType.STEPS -> game.recordActivity(o.target, null, null)
                ObjectiveType.DISTANCE_M, ObjectiveType.BRISK_MIN -> game.recordActivity(50_000, o.target, o.target)
                ObjectiveType.SLEEP_MIN -> game.recordSleep(o.target)
                else -> game.addSet(o.type, o.target, verified = true)
            }
        }
    }

    @Test
    fun `awakening registers the player and issues a quest`() = runBlocking {
        val events = awaken()
        val p = game.player()!!
        assertEquals("Jinwoo", p.name)
        assertEquals(10, p.baselines.pushups)
        assertEquals("m", p.sexHint)
        assertNotNull(game.todayQuest())
        assertTrue(events.any { it.type == SystemEvent.Type.QUEST_ARRIVED })
        assertEquals(2, game.customStats().size)
    }

    @Test
    fun `settle is idempotent`() = runBlocking {
        awaken()
        game.settle()
        game.settle()
        assertEquals(1, db.quests().since(0).size)
    }

    @Test
    fun `completing every objective clears the quest and pays out`() = runBlocking {
        awaken()
        val before = game.player()!!
        completeToday()
        val p = game.player()!!
        assertEquals(QuestStatus.COMPLETED, game.todayQuest()!!.quest.status)
        assertEquals(1, p.streak)
        assertEquals(1, p.questsCompleted)
        assertTrue(p.level > before.level || p.xp > before.xp)
        assertEquals("CLEARED", db.logs().day(time.today())!!.result)
    }

    @Test
    fun `hand-logged reps pay half`() = runBlocking {
        awaken()
        val q = game.todayQuest()!!
        for (o in q.objectives) {
            if (o.type == ObjectiveType.STEPS) game.recordActivity(o.target, null, null) else game.addSet(o.type, o.target, verified = false)
        }
        val reward = q.quest.xpReward
        val p = game.player()!!
        val total = (1 until p.level).sumOf { Progression.xpToNext(it) } + p.xp
        assertTrue("got $total of $reward", total < reward)
    }

    @Test
    fun `a failed day opens the penalty zone the next morning`() = runBlocking {
        awaken()
        game.recordActivity(1000, null, null)
        time.plusHours(15) // 00:00 next day
        val events = game.settle()
        assertTrue(events.any { it.type == SystemEvent.Type.PENALTY_STARTED })
        val penalty = game.activeQuests().single { it.quest.kind == QuestKind.PENALTY }
        // Never before 07:00.
        assertEquals(7, time.hourOf(penalty.quest.startsAt))
        assertEquals(PenaltyEngine.PENALTY_WINDOW_HOURS * 3_600_000, penalty.quest.deadline - penalty.quest.startsAt)
        val p = game.player()!!
        assertEquals(0, p.streak)
        assertTrue(p.hp < Progression.maxHp(p))
        assertEquals(1, p.questsFailed)
        // And today's quest is still issued.
        assertNotNull(game.todayQuest())
    }

    @Test
    fun `penalty steps count only after the zone opens`() = runBlocking {
        awaken()
        time.plusHours(16) // 01:00 next day
        game.settle()
        game.recordActivity(3000, null, null) // before 07:00: doesn't count
        var penalty = game.activeQuests().single { it.quest.kind == QuestKind.PENALTY }
        assertEquals(0, penalty.objectives.single().progress)
        time.plusHours(7) // 08:00
        game.recordActivity(3100, null, null) // sets the baseline
        game.recordActivity(3100 + penalty.objectives.single().target, null, null)
        penalty = db.quests().byId(penalty.quest.id)!!
        assertEquals(QuestStatus.COMPLETED, penalty.quest.status)
        assertTrue(game.player()!!.titles.contains(Titles.ONE_WHO_OVERCAME.name))
    }

    @Test
    fun `an expired penalty weakens the player`() = runBlocking {
        awaken()
        time.plusHours(16)
        game.settle()
        time.plusHours(24)
        val events = game.settle()
        assertTrue(events.any { it.type == SystemEvent.Type.PENALTY_FAILED })
        assertTrue(Progression.isWeakened(game.player()!!, time.today()))
    }

    @Test
    fun `a long absence costs one penalty, not one per day`() = runBlocking {
        awaken()
        time.plusHours(24 * 10)
        game.settle()
        val p = game.player()!!
        assertEquals(1, p.questsFailed) // the one quest that existed; empty days add a single absence hit
        assertEquals(1, game.activeQuests().count { it.quest.kind == QuestKind.PENALTY })
        assertEquals(time.today() - 1, p.lastSettledDay)
    }

    @Test
    fun `recovery day excuses the quest`() = runBlocking {
        awaken()
        assertTrue(game.declareRecovery())
        time.plusHours(16)
        val events = game.settle()
        assertFalse(events.any { it.type == SystemEvent.Type.PENALTY_STARTED })
        assertEquals(0, game.player()!!.questsFailed)
        assertEquals("RECOVERY", db.logs().day(time.today() - 1)!!.result)
    }

    @Test
    fun `death rebirths a level lower`() = runBlocking {
        awaken()
        // Force the player to level 3 with almost no HP, then fail a day.
        db.player().save(game.player()!!.copy(level = 3, hp = 1))
        time.plusHours(16)
        val events = game.settle()
        assertTrue(events.any { it.type == SystemEvent.Type.DEATH })
        val p = game.player()!!
        assertEquals(2, p.level)
        assertEquals(1, p.deaths)
        assertTrue(p.titles.contains(Titles.REBORN.name))
    }

    @Test
    fun `weight milestones pay xp and titles`() = runBlocking {
        awaken()
        val events = game.addWeight(90.5)
        assertTrue(events.any { it.type == SystemEvent.Type.WEIGHT })
        assertTrue(game.player()!!.titles.contains(Titles.FIRST_STEP.name))
        assertEquals(90.5, game.player()!!.weightKg, 0.001)
        // Re-reporting the same loss pays nothing.
        assertTrue(game.addWeight(90.6).none { it.type == SystemEvent.Type.WEIGHT })
    }

    @Test
    fun `rank-up trial promotes on completion`() = runBlocking {
        awaken()
        db.player().save(
            game.player()!!.copy(
                level = 6, streak = 30, weightKg = 80.0,
                baselines = Baselines(pushups = 40, squats = 60, situps = 45, plankSec = 180, avgSteps = 12000, assessedDay = time.today()),
            ),
        )
        assertTrue(game.acceptTrial())
        val trial = game.activeQuests().single { it.quest.kind == QuestKind.TRIAL }
        assertEquals(time.today(), trial.quest.day) // 09:00 → today
        for (o in trial.objectives) {
            if (o.type == ObjectiveType.STEPS) game.recordActivity(o.target, null, null)
            else if (o.type == ObjectiveType.BRISK_MIN) game.recordActivity(o.target * 0, null, o.target)
            else game.addSet(o.type, o.target, true)
        }
        assertEquals(Rank.D, game.player()!!.rank)
        assertNull(RankEngine.trialAvailable(game.player()!!, 0, time.today()))
    }

    @Test
    fun `ai plans are sanitized when replacing the quest`() = runBlocking {
        awaken()
        val ok = game.replaceTodayPlan(
            QuestPlan(QuestKind.DAILY, "Daily Quest: Iron", "Starve yourself.", listOf(ObjectivePlan(ObjectiveType.PUSHUPS, 999), ObjectivePlan(ObjectiveType.STEPS, 7000)), 150, "AI"),
        )
        assertTrue(ok)
        val q = game.todayQuest()!!
        assertEquals("AI", q.quest.source)
        assertFalse(q.quest.flavor.contains("Starve"))
        assertEquals(50, q.objectives.single { it.type == ObjectiveType.PUSHUPS }.target)
    }

    @Test
    fun `stats can be allocated after a level-up`() = runBlocking {
        awaken()
        db.player().save(game.player()!!.copy(freePoints = 2))
        val str = game.player()!!.str
        assertTrue(game.allocate(StatType.STR))
        assertEquals(str + 1, game.player()!!.str)
        assertEquals(1, game.player()!!.freePoints)
    }

    @Test
    fun `clearing the daily quest pays gold`() = runBlocking {
        awaken()
        completeToday()
        val p = game.player()!!
        assertEquals(Gold.forDaily(p.rank), p.gold)
    }

    @Test
    fun `the shop takes gold and the item works`() = runBlocking {
        awaken()
        assertFalse(game.buy(Item.STAMINA_TONIC))
        val p0 = game.player()!!
        db.player().save(p0.copy(gold = 200, fatigue = 60))
        assertTrue(game.buy(Item.STAMINA_TONIC))
        assertEquals(80, game.player()!!.gold)
        assertEquals(1, db.player().inventoryItem(Item.STAMINA_TONIC.id)!!.count)
        assertTrue(game.use(Item.STAMINA_TONIC))
        assertEquals(20, game.player()!!.fatigue)
        assertEquals(0, db.player().inventoryItem(Item.STAMINA_TONIC.id)!!.count)
        assertFalse(game.use(Item.STAMINA_TONIC))
    }

    @Test
    fun `a ward keeps the streak through one failed day`() = runBlocking {
        awaken()
        completeToday()
        val p0 = game.player()!!
        db.player().save(p0.copy(streakWards = 1))
        time.plusHours(24) // next day, nothing done
        game.settle()
        time.plusHours(24)
        game.settle()
        val p = game.player()!!
        assertEquals(1, p.streak)
        assertEquals(0, p.streakWards)
        assertEquals(1, p.questsFailed)
    }
}
