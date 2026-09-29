package dev.aoidoki.arise.lock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.aoidoki.arise.data.AriseDatabase
import dev.aoidoki.arise.data.LockSettings
import dev.aoidoki.arise.data.LockStore
import dev.aoidoki.arise.engine.FakeTime
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.QuestKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

class FakeLockStore : LockStore {
    val s = MutableStateFlow(LockSettings())
    override val lockFlow = s
    override suspend fun lock() = s.value
    override suspend fun setLockEnabled(v: Boolean) = s.update { it.copy(enabled = v) }
    override suspend fun setLockCodes(salt: String, codeHash: String, recoveryHash: String) =
        s.update { it.copy(codeSalt = salt, codeHash = codeHash, recoveryHash = recoveryHash, fails = 0, retryAt = 0) }
    override suspend fun setLockAllow(v: Set<String>) = s.update { it.copy(allow = v) }
    override suspend fun setLockAttempts(fails: Int, retryAt: Long) = s.update { it.copy(fails = fails, retryAt = retryAt) }
    override suspend fun setLockOverridden(questId: Long) = s.update { it.copy(overriddenQuestId = questId) }
    override suspend fun setLockTestUntil(t: Long) = s.update { it.copy(testUntil = t) }
    override suspend fun setNight(enabled: Boolean, start: Int, end: Int) = s.update { it.copy(nightEnabled = enabled, nightStart = start, nightEnd = end) }
    override suspend fun setNightLiftedUntil(t: Long) = s.update { it.copy(nightLiftedUntil = t) }
}

class OverrideCodeTest {
    @Test
    fun `codes hash and verify`() {
        val salt = OverrideCode.newSalt()
        val h = OverrideCode.hash("482913", salt)
        assertTrue(OverrideCode.matches("482913", salt, h))
        assertFalse(OverrideCode.matches("482914", salt, h))
        assertFalse(OverrideCode.matches("", salt, h))
        assertNotEquals(h, OverrideCode.hash("482913", OverrideCode.newSalt()))
    }

    @Test
    fun `recovery codes are forgiving about how they're typed`() {
        val code = OverrideCode.newRecoveryCode()
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
        val salt = OverrideCode.newSalt()
        val h = OverrideCode.hash(code, salt)
        assertTrue(OverrideCode.matches(code.lowercase().replace("-", " "), salt, h))
    }

    @Test
    fun `short or non-numeric codes are rejected`() {
        assertFalse(OverrideCode.valid("12345"))
        assertFalse(OverrideCode.valid("12345a"))
        assertTrue(OverrideCode.valid("123456"))
    }

    @Test
    fun `backoff starts after five misses and doubles`() {
        assertEquals(0, OverrideCode.backoffMillis(4))
        assertEquals(60_000, OverrideCode.backoffMillis(5))
        assertEquals(120_000, OverrideCode.backoffMillis(6))
        assertEquals(3_600_000, OverrideCode.backoffMillis(40))
    }
}

class LockPolicyTest {
    private val own = "dev.aoidoki.arise"

    @Test
    fun `essentials stay usable and the rest is blocked`() {
        val extra = setOf("com.example.sms")
        fun ok(p: String, test: Boolean = false) = LockPolicy.isAllowed(p, own, extra, setOf("com.spotify.music"), test)
        assertTrue(ok("com.android.phone"))
        assertTrue(ok("com.samsung.android.dialer"))
        assertTrue(ok("com.samsung.android.emergency.panic"))
        assertTrue(ok("com.sec.android.app.clockpackage"))
        assertTrue(ok("com.example.sms"))
        assertTrue(ok("com.spotify.music"))
        assertTrue(ok(own))
        assertFalse(ok(own, test = true))
        assertFalse(ok("com.instagram.android"))
        assertFalse(ok("com.android.settings"))
        assertFalse(ok("com.sec.android.app.launcher"))
    }

    private fun utc(h: Int, m: Int = 0, day: Int = 28) = LocalDateTime.of(2026, 9, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val night = LockSettings(codeHash = "x", nightEnabled = true, nightStart = 23 * 60, nightEnd = 6 * 60 + 30)
    private fun at(ms: Long, s: LockSettings = night) = LockPolicy.state(s, emptyList(), ms, ZoneOffset.UTC)

    @Test
    fun `the night window crosses midnight`() {
        assertTrue(at(utc(23, 30)).night)
        assertTrue(at(utc(3)).night)
        assertTrue(at(utc(6, 29)).night)
        assertFalse(at(utc(6, 30)).engaged)
        assertFalse(at(utc(12)).engaged)
        assertFalse(at(utc(22, 59)).engaged)
        assertEquals(utc(6, 30, day = 29), at(utc(23, 30)).until)
        assertEquals(utc(6, 30), at(utc(3)).until)
    }

    @Test
    fun `a same-day window and an empty one`() {
        val nap = night.copy(nightStart = 13 * 60, nightEnd = 14 * 60)
        assertTrue(at(utc(13, 30), nap).night)
        assertFalse(at(utc(14), nap).engaged)
        assertFalse(at(utc(13), night.copy(nightStart = 60, nightEnd = 60)).engaged)
    }

    @Test
    fun `off unless enabled, and the override lasts only until morning`() {
        assertFalse(at(utc(1), night.copy(nightEnabled = false)).engaged)
        val lifted = night.copy(nightLiftedUntil = utc(6, 30))
        assertFalse(at(utc(1), lifted).engaged)
        assertTrue(at(utc(23, 30), lifted).night)
    }

    @Test
    fun `not armed without a code`() {
        val s = LockPolicy.state(LockSettings(enabled = true, testUntil = Long.MAX_VALUE), emptyList(), 0, ZoneOffset.UTC)
        assertFalse(s.engaged)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PenaltyLockTest {
    private lateinit var db: AriseDatabase
    private lateinit var time: FakeTime
    private lateinit var game: Game
    private lateinit var store: FakeLockStore
    private lateinit var lock: PenaltyLock
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @Before
    fun setUp() {
        db = AriseDatabase.inMemory(ApplicationProvider.getApplicationContext())
        time = FakeTime(LocalDateTime.of(2026, 9, 28, 9, 0))
        game = Game(db, time)
        store = FakeLockStore()
        lock = PenaltyLock(store, game, time, scope)
        runBlocking {
            game.awaken(Game.Profile(name = "Jinwoo", age = 24, heightCm = 180.0, weightKg = 92.0, goalWeightKg = 80.0, about = "", customStats = emptyList()))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Fail today, then step into tomorrow's penalty window. */
    private fun intoPenalty() = runBlocking {
        time.plusHours(15)
        game.settle()
        val p = game.activeQuests().single { it.quest.kind == QuestKind.PENALTY }
        time.nowMillis = p.quest.startsAt + 60_000
        p
    }

    private fun arm(code: String = "482913") = runBlocking {
        val recovery = lock.setCode(code)
        assertEquals(PenaltyLock.Attempt.Accepted, lock.setEnabled(true))
        recovery
    }

    @Test
    fun `the lock engages only inside the penalty window`() = runBlocking {
        arm()
        assertFalse(lock.now().engaged)
        time.plusHours(15)
        game.settle()
        // Midnight: the penalty exists but its window opens at 07:00.
        assertFalse(lock.now().engaged)
        val p = game.activeQuests().single { it.quest.kind == QuestKind.PENALTY }
        time.nowMillis = p.quest.startsAt + 1
        assertTrue(lock.now().engaged)
        time.nowMillis = p.quest.deadline
        assertFalse(lock.now().engaged)
    }

    @Test
    fun `walking off the penalty lifts the lock`() = runBlocking {
        arm()
        val p = intoPenalty()
        assertTrue(lock.now().engaged)
        game.recordActivity(100, null, null)
        game.recordActivity(100 + p.objectives.single().target + 10, null, null)
        assertFalse(lock.now().engaged)
    }

    @Test
    fun `the override lifts the lock, is recorded, and the penalty stays`() = runBlocking {
        val recovery = arm()
        intoPenalty()
        assertTrue(lock.override("000000") is PenaltyLock.Attempt.Wrong)
        assertTrue(lock.now().engaged)
        assertEquals(PenaltyLock.Attempt.Accepted, lock.override(recovery))
        assertFalse(lock.now().engaged)
        assertEquals(1, game.player()!!.overrides)
        assertTrue(game.activeQuests().any { it.quest.kind == QuestKind.PENALTY })
    }

    @Test
    fun `turning the lock off needs the code only while engaged`() = runBlocking {
        arm()
        assertEquals(PenaltyLock.Attempt.Accepted, lock.setEnabled(false))
        arm()
        intoPenalty()
        assertTrue(lock.setEnabled(false) is PenaltyLock.Attempt.Wrong)
        assertTrue(store.s.value.enabled)
        assertEquals(PenaltyLock.Attempt.Accepted, lock.setEnabled(false, "482913"))
        assertFalse(lock.now().engaged)
    }

    @Test
    fun `repeated wrong codes make you wait`() = runBlocking {
        arm()
        intoPenalty()
        repeat(4) { assertTrue(lock.override("111111") is PenaltyLock.Attempt.Wrong) }
        val w = lock.override("111111")
        assertTrue(w is PenaltyLock.Attempt.Wait)
        // Even the right code waits.
        assertTrue(lock.override("482913") is PenaltyLock.Attempt.Wait)
        time.nowMillis = (w as PenaltyLock.Attempt.Wait).until
        assertEquals(PenaltyLock.Attempt.Accepted, lock.override("482913"))
    }

    @Test
    fun `a test lock runs thirty seconds without a penalty`() = runBlocking {
        arm()
        lock.test()
        assertTrue(lock.now().test)
        time.nowMillis += PenaltyLock.TEST_MILLIS
        assertFalse(lock.now().engaged)
    }

    @Test
    fun `the night override lifts tonight only and is recorded`() = runBlocking {
        lock.setCode("482913")
        assertEquals(PenaltyLock.Attempt.Accepted, lock.setNight(true, 23 * 60, 6 * 60 + 30))
        time.nowMillis = LocalDateTime.of(2026, 9, 28, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(lock.now().night)
        // Changing the schedule while it's engaged needs the code.
        assertTrue(lock.setNight(false, 23 * 60, 6 * 60 + 30) is PenaltyLock.Attempt.Wrong)
        assertTrue(lock.now().night)
        assertEquals(PenaltyLock.Attempt.Accepted, lock.override("482913"))
        assertFalse(lock.now().engaged)
        assertEquals(1, game.player()!!.overrides)
        time.plusHours(24)
        assertTrue(lock.now().night)
    }

    @Test
    fun `enabling needs a code`() = runBlocking {
        assertTrue(lock.setEnabled(true) is PenaltyLock.Attempt.Wrong)
        assertFalse(store.s.value.enabled)
    }
}
