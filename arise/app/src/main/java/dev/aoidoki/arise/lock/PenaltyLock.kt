package dev.aoidoki.arise.lock

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dev.aoidoki.arise.core.TimeSource
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.data.LockStore
import dev.aoidoki.arise.engine.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The Penalty Lock: while a Penalty Quest window is open, [PenaltyLockService] covers every app
 * except the essentials until the steps are walked, or the override code is entered.
 */
class PenaltyLock(
    private val settings: LockStore,
    private val game: Game,
    private val time: TimeSource,
    scope: CoroutineScope,
    active: Flow<List<QuestWithObjectives>> = game.observeActiveQuests(),
) {
    sealed interface Attempt {
        data object Accepted : Attempt
        data class Wrong(val triesBeforeWait: Int) : Attempt
        data class Wait(val until: Long) : Attempt
    }

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(2_000)
        }
    }

    val state: StateFlow<LockState> =
        combine(settings.lockFlow, active, ticker) { lock, quests, _ -> LockPolicy.state(lock, quests, time.now()) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, LockState.Off)

    /** The lock as of this instant (the [state] flow can trail by a tick). */
    suspend fun now(): LockState = LockPolicy.state(settings.lock(), game.activeQuests(), time.now())

    /** Sets (or replaces) the override code. Returns the new recovery code, shown to the player once. */
    suspend fun setCode(code: String): String {
        require(OverrideCode.valid(code))
        val salt = OverrideCode.newSalt()
        val recovery = OverrideCode.newRecoveryCode()
        settings.setLockCodes(salt, OverrideCode.hash(code, salt), OverrideCode.hash(recovery, salt))
        return recovery
    }

    /** Checks the override code or the recovery code, with backoff after repeated misses. */
    suspend fun verify(input: String): Attempt {
        val l = settings.lock()
        val now = time.now()
        if (now < l.retryAt) return Attempt.Wait(l.retryAt)
        val ok = OverrideCode.matches(input, l.codeSalt, l.codeHash) || OverrideCode.matches(input, l.codeSalt, l.recoveryHash)
        if (!ok) {
            val fails = l.fails + 1
            val wait = OverrideCode.backoffMillis(fails)
            settings.setLockAttempts(fails, if (wait > 0) now + wait else 0)
            return if (wait > 0) Attempt.Wait(now + wait) else Attempt.Wrong(OverrideCode.FREE_TRIES - fails)
        }
        if (l.fails != 0) settings.setLockAttempts(0, 0)
        return Attempt.Accepted
    }

    /** The override: lifts the lock for the current penalty (or ends a test). */
    suspend fun override(input: String): Attempt {
        val s = now()
        val a = verify(input)
        if (a != Attempt.Accepted) return a
        if (s.test) {
            settings.setLockTestUntil(0)
        } else {
            val q = s.quest
            if (q != null) {
                settings.setLockOverridden(q.quest.id)
                game.recordOverride()
            }
        }
        return a
    }

    /** Turning the lock on needs a code; turning it off needs the code only while it's engaged. */
    suspend fun setEnabled(on: Boolean, code: String = ""): Attempt {
        if (on) {
            if (!settings.lock().hasCode) return Attempt.Wrong(OverrideCode.FREE_TRIES)
        } else if (now().engaged) {
            val a = verify(code)
            if (a != Attempt.Accepted) return a
            settings.setLockTestUntil(0)
        }
        settings.setLockEnabled(on)
        return Attempt.Accepted
    }

    suspend fun setAllow(packages: Set<String>) = settings.setLockAllow(packages)

    /** Engages the lock for 30 seconds so the player can see it (and practise the override). */
    suspend fun test() = settings.setLockTestUntil(time.now() + TEST_MILLIS)

    companion object {
        const val TEST_MILLIS = 30_000L

        fun serviceEnabled(context: Context): Boolean {
            val want = ComponentName(context, PenaltyLockService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == want }
        }
    }
}
