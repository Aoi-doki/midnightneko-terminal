package dev.aoidoki.arise.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.content.pm.ServiceInfo
import android.os.Build
import dev.aoidoki.arise.data.SettingsStore
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestPlan
import dev.aoidoki.arise.engine.QuestPlanner
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.engine.RankEngine
import dev.aoidoki.arise.engine.SafetyLimits
import dev.aoidoki.arise.graph
import dev.aoidoki.arise.work.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the on-device model plugs into the game. Every entry point returns false/null on any
 * failure, and every plan it produces goes through [SafetyLimits] inside [Game] — the model can
 * make the System smarter, never less safe, and never required.
 */
class AiDirector(
    context: Context,
    private val models: ModelManager,
    private val llm: LlmEngine,
    private val game: Game,
    private val settings: SettingsStore,
) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _busy = MutableStateFlow<String?>(null)
    /** What the AI is doing right now, for the UI ("Analyzing Player…"), or null. */
    val busy: StateFlow<String?> = _busy

    suspend fun available(): Boolean = settings.current().aiEnabled && models.ready() != null

    private suspend fun <T> task(label: String, block: suspend (ModelState.Ready) -> T?): T? {
        if (!settings.current().aiEnabled) return null
        val model = models.ready() ?: return null
        _busy.value = label
        return try {
            block(model)
        } catch (t: Throwable) {
            Log.w(TAG, "$label failed", t)
            null
        } finally {
            _busy.value = null
        }
    }

    /** Read the Player's own words and refine the starting assessment. */
    suspend fun assess(): Boolean = task("Analyzing Player…") { model ->
        val p = game.player() ?: return@task null
        val stats = game.customStats()
        val raw = llm.generate(model, Prompts.assessment(p, stats), temperature = 0.4f) ?: return@task null
        val a = AiJson.parseAssessment(raw) ?: llm.generate(model, repair(raw), temperature = 0.2f)?.let { AiJson.parseAssessment(it) } ?: return@task null
        game.applyAiAssessment(Game.AiAssessment(a.summary, a.stats, a.limitations, a.baselines, null))
        prefs.edit().putString("assessed_with", model.label).apply()
        true
    } ?: false

    /** True when the installed core hasn't read this Player yet (e.g. it finished downloading after the Awakening). */
    fun needsAssessment(): Boolean {
        val model = models.ready() ?: return false
        return prefs.getString("assessed_with", null) != model.label
    }

    /** Rewrite today's (untouched) Daily Quest around the Player. */
    suspend fun refineToday(reroll: Boolean = false): Boolean = task(if (reroll) "Rewriting the quest…" else "Calibrating today's quest…") { model ->
        val p = game.player() ?: return@task null
        val today = game.todayQuest() ?: return@task null
        if (today.quest.status != QuestStatus.ACTIVE) return@task null
        if (!reroll && today.quest.source == "AI") return@task null
        val ctx = game.context(p)
        val rules = QuestPlanner.daily(ctx)
        val current = QuestPlan(QuestKind.DAILY, today.quest.title, today.quest.flavor, today.objectives.map { dev.aoidoki.arise.engine.ObjectivePlan(it.type, it.target) }, today.quest.xpReward)
        val history = "completion over the last week ${(ctx.recentCompletion * 100).toInt()}%, average steps ${ctx.avgSteps7d}"
        val prompt = Prompts.dailyQuest(p, game.customStats(), rules, history, if (reroll) current else null)
        val raw = llm.generate(model, prompt, temperature = if (reroll) 0.9f else 0.7f) ?: return@task null
        val plan = AiJson.parseQuest(raw, QuestKind.DAILY, rules.xpReward)
            ?: llm.generate(model, repair(raw), temperature = 0.2f)?.let { AiJson.parseQuest(it, QuestKind.DAILY, rules.xpReward) }
            ?: return@task null
        game.replaceTodayPlan(plan, mpCost = if (reroll) 30 else 0).takeIf { it }
    } ?: false

    /** A model-written Rank-Up Trial (targets kept within the rules trial's envelope). */
    suspend fun trialPlan(): QuestPlan? = task("Designing the trial…") { model ->
        val p = game.player() ?: return@task null
        val power = game.power() ?: return@task null
        val target = RankEngine.trialAvailable(p, power.total, game.time.today()) ?: return@task null
        val rules = QuestPlanner.trial(game.context(p), target)
        val raw = llm.generate(model, Prompts.trial(p, game.customStats(), rules, target), temperature = 0.7f) ?: return@task null
        val ai = AiJson.parseQuest(raw, QuestKind.TRIAL, rules.xpReward) ?: return@task null
        val byType = rules.objectives.associateBy { it.type }
        // Same objective types as the rules trial, each within ±15% of it.
        val objectives = ai.objectives.mapNotNull { o ->
            val r = byType[o.type] ?: return@mapNotNull null
            o.copy(target = o.target.coerceIn((r.target * 0.85).toInt(), (r.target * 1.15).toInt()))
        }
        if (objectives.size < rules.objectives.size / 2) return@task null
        val title = ai.title.takeIf { it.isNotBlank() && SafetyLimits.isSafeText(it) } ?: rules.title
        val flavor = ai.flavor.takeIf { it.isNotBlank() && SafetyLimits.isSafeText(it) } ?: rules.flavor
        rules.copy(title = title.take(80), flavor = flavor.take(400), objectives = objectives, source = "AI")
    }

    /** A short evaluation of the Player's Hunter Power for the Rank screen. */
    suspend fun evaluate(): String? = task("Evaluating…") { model ->
        val p = game.player() ?: return@task null
        val power = game.power() ?: return@task null
        val raw = llm.generate(model, Prompts.evaluation(p, power), temperature = 0.6f) ?: return@task null
        AiJson.parseText(raw)?.takeIf { SafetyLimits.isSafeText(it) }
    }

    private fun repair(bad: String): String = Prompts.chat(
        "Rewrite the following as ONE valid JSON object only, keeping its content. No prose.\n\n${bad.take(1500)}",
    )

    companion object {
        private const val TAG = "AiDirector"

        /** Run AI work in the background (expedited, foreground if needed). */
        fun enqueue(context: Context, job: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ai-$job", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AiWorker>()
                    .setInputData(workDataOf("job" to job))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}

class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.work(applicationContext, "The System is thinking…")
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifications.ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifications.ID_WORK, n)
    }

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (!g.ai.available()) return Result.success()
        when (inputData.getString("job")) {
            "assess" -> {
                g.ai.assess()
                g.ai.refineToday()
            }
            "daily" -> g.ai.refineToday()
        }
        return Result.success()
    }
}
