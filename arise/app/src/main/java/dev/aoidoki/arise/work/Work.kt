package dev.aoidoki.arise.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.engine.SystemEvent
import dev.aoidoki.arise.graph
import dev.aoidoki.arise.sense.StepTrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object Scheduler {
    private const val SYNC = "arise-sync"

    const val ACTION_MIDNIGHT = "dev.aoidoki.arise.MIDNIGHT"
    const val ACTION_WARNING = "dev.aoidoki.arise.WARNING"
    const val ACTION_MORNING = "dev.aoidoki.arise.MORNING"

    const val WARNING_HOUR = 21
    const val MORNING_HOUR = 7

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            SYNC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
        )
        scheduleAlarms(context)
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$SYNC-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }

    fun scheduleAlarms(context: Context) {
        val t = context.graph.time
        val now = t.now()
        val today = t.today()
        fun next(hour: Int, minute: Int = 0): Long {
            val at = t.at(today, hour, minute)
            return if (at > now) at else t.at(today + 1, hour, minute)
        }
        setAlarm(context, ACTION_MIDNIGHT, t.startOfDay(today + 1) + 30_000)
        setAlarm(context, ACTION_WARNING, next(WARNING_HOUR))
        setAlarm(context, ACTION_MORNING, next(MORNING_HOUR))
    }

    private fun setAlarm(context: Context, action: String, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Real wall-clock time, not the game clock's offset.
        val wall = at - context.graph.time.offsetMillis
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wall, pi)
        }
    }
}

/** Every 15 minutes: settle, pull Health Connect, keep the tracker alive, and the evening warning as a backstop. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        return try {
            g.game.settle()
            Sync.healthConnect(applicationContext)
            if (g.settings.current().trackingEnabled) StepTrackerService.start(applicationContext)
            if (g.time.hourOf(g.time.now()) >= Scheduler.WARNING_HOUR) Sync.eveningWarning(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "sync failed", e)
            Result.retry()
        }
    }
}

object Sync {
    private const val PREFS = "sync"

    suspend fun healthConnect(context: Context) {
        val g = context.graph
        val hc = g.health
        if (!hc.available() || !hc.hasCore()) return
        val today = g.time.today()
        val start = Instant.ofEpochMilli(g.time.startOfDay(today))
        val day = hc.day(start, Instant.ofEpochMilli(g.time.now()))
        if (day.steps != null) g.game.recordActivity(day.steps, day.distanceM, null)
        hc.sleepLastNight(start)?.let { g.game.recordSleep(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("weights_since", g.time.now() - 30L * 86_400_000)
        val weights = hc.weightsSince(Instant.ofEpochMilli(since))
        for (w in weights.sortedBy { it.time }) g.game.addWeight(w.kg, "HEALTH_CONNECT", w.time.toEpochMilli())
        prefs.edit().putLong("weights_since", g.time.now()).apply()
    }

    /** 3 hours before midnight, once per day, if the Daily Quest is still open. */
    suspend fun eveningWarning(context: Context) {
        val g = context.graph
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = g.time.today()
        if (prefs.getLong("warned_day", -1) == today) return
        val q = g.game.todayQuest() ?: return
        if (q.quest.status != QuestStatus.ACTIVE) return
        val p = g.game.player() ?: return
        if (p.recoveryDay == today) return
        prefs.edit().putLong("warned_day", today).apply()
        val left = q.objectives.filter { !it.done }.joinToString(", ") { "${it.type.label} ${it.type.format(it.target - it.progress)}" }
        g.game.broadcast(
            SystemEvent(
                SystemEvent.Type.WARNING, "Warning",
                "The Daily Quest is ${(q.completion * 100).roundToInt()}% complete. Remaining: $left. " +
                    "Failure to complete the Daily Quest will result in an appropriate penalty.",
                "Warning. Failure to complete the daily quest will result in an appropriate penalty.",
            ),
        )
    }

    suspend fun morning(context: Context) {
        val g = context.graph
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = g.time.today()
        if (prefs.getLong("morning_day", -1) == today) return
        prefs.edit().putLong("morning_day", today).apply()
        val active = g.game.activeQuests()
        val penalty = active.firstOrNull { it.quest.kind == QuestKind.PENALTY && g.time.dayOf(it.quest.startsAt) == today }
        if (penalty != null) {
            val steps = penalty.objectives.firstOrNull { it.type == ObjectiveType.STEPS }?.target ?: 0
            g.game.broadcast(
                SystemEvent(
                    SystemEvent.Type.PENALTY_STARTED, "Penalty Zone",
                    "The Penalty Zone is open. Survive: walk ${"%,d".format(steps)} steps within ${dev.aoidoki.arise.engine.PenaltyEngine.PENALTY_WINDOW_HOURS} hours.",
                    "The penalty zone is now open. Survive.",
                ),
            )
        } else {
            g.game.todayQuest()?.takeIf { it.quest.status == QuestStatus.ACTIVE }?.let {
                g.game.broadcast(SystemEvent(SystemEvent.Type.QUEST_ARRIVED, "Daily Quest", it.quest.title, "Good morning, Player. The daily quest awaits."))
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val g = context.graph
                when (intent.action) {
                    Scheduler.ACTION_MIDNIGHT -> g.game.settle()
                    Scheduler.ACTION_WARNING -> Sync.eveningWarning(context)
                    Scheduler.ACTION_MORNING -> { g.game.settle(); Sync.morning(context) }
                }
                if (g.settings.flow.first().trackingEnabled) StepTrackerService.start(context)
            } catch (e: Exception) {
                Log.w("AlarmReceiver", "alarm failed", e)
            } finally {
                Scheduler.scheduleAlarms(context)
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val g = context.graph
                Scheduler.schedule(context)
                if (g.settings.flow.first().trackingEnabled) StepTrackerService.start(context)
                g.game.settle()
            } finally {
                pending.finish()
            }
        }
    }
}
