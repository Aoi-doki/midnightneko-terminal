package dev.aoidoki.arise.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aoidoki.arise.ai.AiDirector
import dev.aoidoki.arise.ai.ModelSpec
import dev.aoidoki.arise.ai.ModelState
import dev.aoidoki.arise.data.AppSettings
import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.data.DayLogEntity
import dev.aoidoki.arise.data.EventEntity
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.data.VoiceSettings
import dev.aoidoki.arise.data.WeightEntity
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.engine.RankEngine
import dev.aoidoki.arise.engine.StatType
import dev.aoidoki.arise.engine.SystemEvent
import dev.aoidoki.arise.graph
import dev.aoidoki.arise.sense.StepTrackerService
import dev.aoidoki.arise.work.Scheduler
import dev.aoidoki.arise.data.Backup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the screens draw, in one immutable snapshot. */
data class UiState(
    val loaded: Boolean = false,
    val player: PlayerEntity? = null,
    val customStats: List<CustomStatEntity> = emptyList(),
    val quests: List<QuestWithObjectives> = emptyList(),
    val todayLog: DayLogEntity? = null,
    val days: List<DayLogEntity> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    val log: List<EventEntity> = emptyList(),
    val inventory: Map<String, Int> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val power: RankEngine.Power? = null,
    val trialRank: Rank? = null,
    val model: ModelState = ModelState.None,
    val aiBusy: String? = null,
    val evaluation: String? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val daily: QuestWithObjectives? get() = quests.firstOrNull { it.quest.kind == QuestKind.DAILY }
    val penalty: QuestWithObjectives? get() = quests.firstOrNull { it.quest.kind == QuestKind.PENALTY }
    val trial: QuestWithObjectives? get() = quests.firstOrNull { it.quest.kind == QuestKind.TRIAL }
    val today: Long get() = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** The UI turns red only while the Penalty Zone is actually open. */
    val inPenaltyZone: Boolean get() = penalty?.let { now >= it.quest.startsAt } ?: false
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val extra = MutableStateFlow(Extra())
    private data class Extra(val power: RankEngine.Power? = null, val trial: Rank? = null, val evaluation: String? = null, val now: Long = System.currentTimeMillis())

    private val _popups = MutableStateFlow<List<SystemEvent>>(emptyList())
    val popups: StateFlow<List<SystemEvent>> = _popups.asStateFlow()

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<UiState> = combine(
        listOf(
            g.game.observePlayer(), g.game.observeCustomStats(), g.game.observeActiveQuests(), g.game.observeToday(),
            g.game.observeDays(), g.game.observeWeights(), g.game.observeEventLog(), g.settings.flow,
            g.models.state, g.ai.busy, extra, g.game.observeInventory(),
        ),
    ) { v ->
        val e = v[10] as Extra
        UiState(
            loaded = true,
            player = v[0] as PlayerEntity?,
            customStats = v[1] as List<CustomStatEntity>,
            quests = v[2] as List<QuestWithObjectives>,
            todayLog = v[3] as DayLogEntity?,
            days = v[4] as List<DayLogEntity>,
            weights = v[5] as List<WeightEntity>,
            log = v[6] as List<EventEntity>,
            settings = v[7] as AppSettings,
            model = v[8] as ModelState,
            aiBusy = v[9] as String?,
            power = e.power,
            trialRank = e.trial,
            evaluation = e.evaluation,
            now = e.now,
            inventory = (v[11] as List<dev.aoidoki.arise.data.InventoryEntity>).associate { it.itemId to it.count },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch { g.game.events.collect { ev -> if (ev.type != SystemEvent.Type.QUEST_PROGRESS) _popups.update { it + ev } } }
        viewModelScope.launch {
            // Clock tick for countdowns, and a slow refresh of derived numbers.
            var n = 0
            while (true) {
                extra.update { it.copy(now = g.time.now()) }
                if (n++ % 15 == 0) refreshPower()
                if (n % 2 == 0) g.models.refresh()
                delay(1000)
            }
        }
    }

    private suspend fun refreshPower() {
        val p = g.game.player() ?: return
        val power = g.game.power()
        val trial = power?.let { RankEngine.trialAvailable(p, it.total, g.time.today()) }
        extra.update { it.copy(power = power, trial = trial) }
    }

    fun dismissPopup() {
        g.voice.stop()
        _popups.update { it.drop(1) }
    }

    fun onResume() = viewModelScope.launch {
        g.game.settle()
        refreshPower()
        Scheduler.syncNow(getApplication())
    }

    fun awaken(profile: Game.Profile) = viewModelScope.launch {
        g.game.awaken(profile)
        refreshPower()
        if (g.settings.current().trackingEnabled) StepTrackerService.start(getApplication())
        AiDirector.enqueue(getApplication(), "assess")
    }

    fun updateProfile(profile: Game.Profile, reassess: Boolean) = viewModelScope.launch {
        g.game.updateProfile(profile)
        refreshPower()
        if (reassess) AiDirector.enqueue(getApplication(), "assess")
    }

    fun buy(item: dev.aoidoki.arise.engine.Item) = viewModelScope.launch { g.game.buy(item) }
    fun use(item: dev.aoidoki.arise.engine.Item) = viewModelScope.launch { g.game.use(item) }

    fun allocate(s: StatType) = viewModelScope.launch { g.game.allocate(s); refreshPower() }
    fun equipTitle(t: String) = viewModelScope.launch { g.game.equipTitle(t) }
    fun recovery() = viewModelScope.launch { g.game.declareRecovery() }

    fun reroll() = viewModelScope.launch {
        if (!(g.ai.available() && g.ai.refineToday(reroll = true))) g.game.reroll()
    }

    fun manual(type: ObjectiveType, amount: Int) = viewModelScope.launch { g.game.addSet(type, amount, verified = false) }
    fun addSet(type: ObjectiveType, amount: Int) = viewModelScope.launch { g.game.addSet(type, amount, verified = true) }

    fun assessment(r: Game.AssessmentResult) = viewModelScope.launch { g.game.completeAssessment(r); refreshPower() }

    fun acceptTrial() = viewModelScope.launch {
        val aiPlan = if (g.ai.available()) g.ai.trialPlan() else null
        g.game.acceptTrial(aiPlan)
        refreshPower()
    }

    fun evaluate() = viewModelScope.launch {
        val text = g.ai.evaluate()
        extra.update { it.copy(evaluation = text ?: "The System requires its core (the on-device model) for a written evaluation. Install it in Settings.") }
    }

    fun addWeight(kg: Double) = viewModelScope.launch {
        g.game.addWeight(kg)
        g.health.writeWeight(kg, java.time.Instant.now())
        refreshPower()
    }

    fun setVoice(v: VoiceSettings) = viewModelScope.launch {
        g.settings.setVoice(v)
        // New voice settings mean new audio: re-render the fixed lines so they stay instant.
        g.voice.prewarm(dev.aoidoki.arise.voice.Lines.all, v)
    }
    fun testVoice(v: VoiceSettings) = g.voice.say("Welcome, Player. The daily quest has arrived. Failure to complete it will result in an appropriate penalty.", v.copy(enabled = true))
    fun voices() = g.voice.voices
    fun say(text: String) = viewModelScope.launch { g.voice.say(text, g.settings.current().voice) }
    fun stopVoice() = g.voice.stop()
    fun isSpeaking(): Boolean = g.voice.speaking.value

    // ---- Penalty Lock ----
    val lockState = g.lock.state
    val lockService = dev.aoidoki.arise.lock.PenaltyLockService.running
    suspend fun setLockCode(code: String): String = g.lock.setCode(code)
    suspend fun setLockEnabled(on: Boolean, code: String) = g.lock.setEnabled(on, code)
    suspend fun setNight(enabled: Boolean, start: Int, end: Int, code: String) = g.lock.setNight(enabled, start, end, code)
    fun testLock() = viewModelScope.launch { g.lock.test() }
    fun setLockAllow(pkgs: Set<String>) = viewModelScope.launch { g.lock.setAllow(pkgs) }

    /** Launchable apps the player may add to the lock's allowlist. Settings apps are left out: they'd undo the lock. */
    fun installedApps(): List<dev.aoidoki.arise.lock.LockApp> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != app.packageName && !pkg.contains("settings") && !dev.aoidoki.arise.lock.LockPolicy.isEssential(pkg) }
            .distinctBy { it.first }
            .map { (pkg, label) -> dev.aoidoki.arise.lock.LockApp(pkg, label) }
            .sortedBy { it.label.lowercase() }
    }

    fun setImperial(b: Boolean) = viewModelScope.launch { g.settings.setImperial(b) }
    fun setWifiOnly(b: Boolean) = viewModelScope.launch { g.settings.setWifiOnly(b) }
    fun setAiEnabled(b: Boolean) = viewModelScope.launch { g.settings.setAiEnabled(b) }
    fun acceptDisclaimer() = viewModelScope.launch { g.settings.setDisclaimer(true) }

    fun setTracking(b: Boolean) = viewModelScope.launch {
        g.settings.setTracking(b)
        if (b) StepTrackerService.start(getApplication()) else StepTrackerService.stop(getApplication())
    }

    fun startTracker() = StepTrackerService.start(getApplication())

    fun downloadModel(spec: ModelSpec) = viewModelScope.launch {
        g.settings.setModelChoice(spec.id)
        g.models.start(spec, g.settings.current().wifiOnly)
    }

    fun cancelModel() = g.models.cancelDownload()
    fun deleteModel() { g.models.delete(); g.llm.release() }
    fun importModel(uri: Uri, name: String) = viewModelScope.launch { if (g.models.import(uri, name)) g.llm.release() }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val json = Backup.export(g.db)
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@launch
        if (Backup.import(g.db, text)) {
            g.game.settle()
            refreshPower()
        }
    }

    fun wipe() = viewModelScope.launch {
        g.db.maintenance().wipe()
        StepTrackerService.stop(getApplication())
    }

    /** Debug/test hook used by the emulator smoke test and the hidden debug menu. */
    fun skipDays(days: Int) = viewModelScope.launch {
        g.game.shiftTime(days * 86_400_000L)
        g.game.settle()
        refreshPower()
    }
}
