package dev.aoidoki.arise.sense

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.graph
import dev.aoidoki.arise.work.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * "The System is watching": a health-type foreground service that counts steps all day from the
 * hardware step counter, feeds the Game, keeps the live quest in the notification shade, and keeps
 * the process alive so the midnight settle and the voice happen on time.
 */
class StepTrackerService : LifecycleService(), SensorEventListener {
    private val prefs by lazy { getSharedPreferences("steps", MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private var state = StepState()
    private var lastPushed = -1
    private var lastPushAt = 0L
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        state = prefs.getString("state", null)?.let { runCatching { json.decodeFromString<StepState>(it) }.getOrNull() } ?: StepState()
        val ok = goForeground("[SYSTEM] The System is watching", "Tracking the Daily Quest.", false, null)
        if (!ok) {
            stopSelf()
            return
        }
        registerSensor()
        observeQuests()
        ticker = lifecycleScope.launch {
            var lastDay = graph.time.today()
            while (isActive) {
                delay(60_000)
                val today = graph.time.today()
                if (today != lastDay) {
                    lastDay = today
                    graph.game.settle()
                }
                push(force = true)
            }
        }
    }

    private fun goForeground(title: String, text: String, penalty: Boolean, progress: Int?): Boolean = try {
        val n = Notifications.tracker(this, title, text, penalty, progress)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, Notifications.ID_TRACKER, n, type)
        true
    } catch (e: Exception) {
        // Missing ACTIVITY_RECOGNITION, or started from the background where Android forbids it.
        Log.w(TAG, "cannot start foreground", e)
        false
    }

    private fun registerSensor() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        // Batched delivery (up to 10 s) keeps the radio and CPU asleep between steps.
        sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val now = graph.time.now()
        state = StepMath.onReading(state, event.values[0].toLong(), graph.time.dayOf(now), now / 60_000)
        prefs.edit().putString("state", json.encodeToString(state)).apply()
        push(force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun push(force: Boolean) {
        val steps = if (state.day == graph.time.today()) state.today else 0
        val now = System.currentTimeMillis()
        if (!force && steps - lastPushed < 20 && now - lastPushAt < 30_000) return
        lastPushed = steps
        lastPushAt = now
        lifecycleScope.launch { graph.game.recordActivity(steps, null, state.briskMinutes.takeIf { state.day == graph.time.today() }) }
    }

    private fun observeQuests() {
        lifecycleScope.launch {
            combine(graph.game.observeActiveQuests(), graph.game.observeToday()) { quests, _ -> quests }.collect { quests ->
                val (title, text, penalty, progress) = describe(quests)
                goForeground(title, text, penalty, progress)
            }
        }
    }

    private data class Line(val title: String, val text: String, val penalty: Boolean, val progress: Int?)

    private fun describe(quests: List<QuestWithObjectives>): Line {
        val now = graph.time.now()
        val penalty = quests.firstOrNull { it.quest.kind == QuestKind.PENALTY }
        if (penalty != null) {
            val o = penalty.objectives.firstOrNull()
            val open = now >= penalty.quest.startsAt
            val left = ((penalty.quest.deadline - now) / 60_000).coerceAtLeast(0)
            val text = if (!open) "Opens at ${clock(penalty.quest.startsAt)}. Prepare yourself."
            else "Survive: ${o?.let { "${it.type.format(it.progress)} / ${it.type.format(it.target)} ${it.type.unit}" } ?: ""} · ${left / 60}h ${left % 60}m left"
            return Line("⚠ PENALTY ZONE", text, true, if (open) (penalty.completion * 100).roundToInt() else null)
        }
        val daily = quests.firstOrNull { it.quest.kind == QuestKind.DAILY && it.quest.day == graph.time.today() }
            ?: return Line("[SYSTEM] Daily Quest complete", "Rest, Player. A new quest arrives at midnight.", false, null)
        val steps = daily.objectives.firstOrNull { it.type == ObjectiveType.STEPS }
        val rest = daily.objectives.filter { it.type != ObjectiveType.STEPS && !it.done }.joinToString(" · ") { "${it.type.label} ${it.progress}/${it.target}" }
        val text = buildString {
            if (steps != null) append("Steps ${"%,d".format(steps.progress)} / ${"%,d".format(steps.target)}")
            if (rest.isNotEmpty()) append(if (isEmpty()) rest else "\n$rest")
        }
        return Line("[SYSTEM] Daily Quest ${(daily.completion * 100).roundToInt()}%", text, false, (daily.completion * 100).roundToInt())
    }

    private fun clock(millis: Long): String {
        val t = java.time.Instant.ofEpochMilli(millis).atZone(graph.time.zone())
        return "%02d:%02d".format(t.hour, t.minute)
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        ticker?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StepTracker"

        fun canRun(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!canRun(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, StepTrackerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepTrackerService::class.java))
        }
    }
}
