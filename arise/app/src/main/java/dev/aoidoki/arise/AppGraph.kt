package dev.aoidoki.arise

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.aoidoki.arise.ai.AiDirector
import dev.aoidoki.arise.ai.LlmEngine
import dev.aoidoki.arise.ai.ModelManager
import dev.aoidoki.arise.ai.ModelState
import dev.aoidoki.arise.core.TimeSource
import dev.aoidoki.arise.data.AriseDatabase
import dev.aoidoki.arise.data.SettingsStore
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.SystemEvent
import dev.aoidoki.arise.lock.PenaltyLock
import dev.aoidoki.arise.sense.HealthRepo
import dev.aoidoki.arise.sense.StepTrackerService
import dev.aoidoki.arise.voice.Lines
import dev.aoidoki.arise.voice.SystemVoice
import dev.aoidoki.arise.work.Notifications
import dev.aoidoki.arise.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hand-rolled dependency graph: one of everything, created by [AriseApp]. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time = TimeSource()
    val db: AriseDatabase by lazy { AriseDatabase.create(context) }
    val game: Game by lazy { Game(db, time) }
    val settings = SettingsStore(context)
    val voice by lazy { SystemVoice(context) }
    val health by lazy { HealthRepo(context) }
    val models by lazy { ModelManager(context) }
    val llm by lazy { LlmEngine(context) }
    val ai by lazy { AiDirector(context, models, llm, game, settings) }
    val lock by lazy { PenaltyLock(settings, game, time, scope) }

    fun start() {
        Notifications.createChannels(context)
        // Bind TTS and render the fixed System lines now, so the first announcement isn't the slow one.
        voice.warmUp()
        scope.launch { voice.prewarm(Lines.all, settings.current().voice) }
        // Every System event is spoken and, when the app isn't on screen, notified.
        scope.launch {
            game.events.collect { e -> dispatch(e) }
        }
        // When the core finishes installing, let it read the Player and rewrite today's quest.
        scope.launch {
            models.state.collect { m ->
                if (m is ModelState.Ready && game.player() != null && ai.needsAssessment()) AiDirector.enqueue(context, "assess")
            }
        }
        scope.launch {
            Scheduler.schedule(context)
            if (game.player() != null) {
                game.settle()
                if (settings.current().trackingEnabled) StepTrackerService.start(context)
            }
        }
    }

    private suspend fun dispatch(e: SystemEvent) {
        val s = settings.flow.first()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        // On screen, the popup speaks its own line the moment it appears (ui/Root.kt EventPopup), so
        // voice and window stay in step. Progress ticks have no popup, and off screen there is none.
        if (!foreground || e.type == SystemEvent.Type.QUEST_PROGRESS) voice.say(e.speech, s.voice)
        if (!foreground && e.type != SystemEvent.Type.QUEST_PROGRESS) Notifications.event(context, e)
        if (e.type == SystemEvent.Type.QUEST_ARRIVED && e.title == "Daily Quest Has Arrived") {
            AiDirector.enqueue(context, "daily")
        }
    }
}
