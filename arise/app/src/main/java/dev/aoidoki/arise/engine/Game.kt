package dev.aoidoki.arise.engine

import dev.aoidoki.arise.core.TimeSource
import dev.aoidoki.arise.data.AriseDatabase
import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.data.DayLogEntity
import dev.aoidoki.arise.data.EventEntity
import dev.aoidoki.arise.data.ObjectiveEntity
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.data.QuestEntity
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.data.WeightEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The System. Owns every rule that changes the Player, behind one lock so the step tracker, the
 * workers and the UI can all poke it concurrently.
 *
 * Every day is settled exactly once, in order, by [settle] — whenever it next runs. A killed app,
 * a dead phone or a missing exact-alarm permission can delay a penalty, never skip or double it.
 */
class Game(private val db: AriseDatabase, val time: TimeSource) {
    private val lock = Mutex()
    private val _events = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 64)

    /** Live announcements for the UI, the voice and notifications. */
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    private val players get() = db.player()
    private val quests get() = db.quests()
    private val logs get() = db.logs()

    // ---- Observation ---------------------------------------------------------------------------

    fun observePlayer(): Flow<PlayerEntity?> = players.observe()
    fun observeCustomStats(): Flow<List<CustomStatEntity>> = players.observeCustomStats()
    fun observeActiveQuests(): Flow<List<QuestWithObjectives>> = quests.observeActive()
    fun observeRecentQuests(limit: Int = 60): Flow<List<QuestWithObjectives>> = quests.observeRecent(limit)
    fun observeToday(): Flow<DayLogEntity?> = logs.observeDay(time.today())
    fun observeDays(limit: Int = 60): Flow<List<DayLogEntity>> = logs.observeRecent(limit)
    fun observeWeights(): Flow<List<WeightEntity>> = logs.observeWeights()
    fun observeEventLog(limit: Int = 200): Flow<List<EventEntity>> = logs.observeEvents(limit)

    suspend fun player(): PlayerEntity? = players.get()
    suspend fun customStats(): List<CustomStatEntity> = players.customStats()
    suspend fun activeQuests(): List<QuestWithObjectives> = quests.active()
    suspend fun todayQuest(): QuestWithObjectives? = quests.forDay(time.today(), QuestKind.DAILY)

    // ---- Awakening -----------------------------------------------------------------------------

    data class Profile(
        val name: String,
        val age: Int,
        val heightCm: Double,
        val weightKg: Double,
        val goalWeightKg: Double,
        val about: String,
        val customStats: List<CustomStatEntity>,
    )

    suspend fun awaken(profile: Profile): List<SystemEvent> = run {
        val today = time.today()
        val reading = ProfileParser.read(profile.about, profile.customStats)
        var p = PlayerEntity(
            name = profile.name.ifBlank { "Player" }.take(24),
            age = profile.age.coerceIn(13, 100),
            heightCm = profile.heightCm.coerceIn(120.0, 230.0),
            weightKg = profile.weightKg.coerceIn(30.0, 350.0),
            startWeightKg = profile.weightKg.coerceIn(30.0, 350.0),
            goalWeightKg = SafetyLimits.safeGoalWeight(profile.goalWeightKg, profile.heightCm),
            about = profile.about.take(4000),
            sexHint = reading.sexHint,
            baselines = reading.baselines,
            limitations = reading.limitations,
            awakenedDay = today,
            lastSettledDay = today - 1,
        )
        p = Progression.initialStats(p)
        for ((stat, bonus) in reading.statBonus) {
            p = Progression.withStat(p, stat, (Progression.stat(p, stat) + bonus).coerceIn(5, 40))
        }
        p = Progression.fullRestore(p).copy(
            assessment = ProfileParser.summary(p.name, BodyMetrics.bmi(p.weightKg, p.heightCm), reading, p.age),
        )
        lock.withLock {
            players.save(p)
            players.replaceCustomStats(profile.customStats)
            logs.insertWeight(WeightEntity(time = time.now(), kg = p.weightKg, source = "AWAKENING"))
        }
        announce(
            listOf(
                SystemEvent(
                    SystemEvent.Type.INFO, "Awakening",
                    "You have acquired the qualifications to be a Player. Welcome, ${p.name}.",
                    "Welcome, Player ${p.name}. You have been chosen.",
                ),
            ),
        )
        settle()
    }

    /** Edit the Player's words and body numbers. Limitations are re-read; measured baselines are never lowered by it. */
    suspend fun updateProfile(profile: Profile) {
        val reading = ProfileParser.read(profile.about, profile.customStats)
        lock.withLock {
            val p = players.get() ?: return
            val b = p.baselines
            val r = reading.baselines
            players.save(
                p.copy(
                    name = profile.name.ifBlank { p.name }.take(24),
                    age = profile.age.coerceIn(13, 100),
                    heightCm = profile.heightCm.coerceIn(120.0, 230.0),
                    goalWeightKg = SafetyLimits.safeGoalWeight(profile.goalWeightKg, profile.heightCm),
                    about = profile.about.take(4000),
                    sexHint = reading.sexHint.ifEmpty { p.sexHint },
                    limitations = reading.limitations.copy(notes = reading.limitations.notes),
                    baselines = b.copy(
                        pushups = max(b.pushups, r.pushups),
                        squats = max(b.squats, r.squats),
                        situps = max(b.situps, r.situps),
                        plankSec = max(b.plankSec, r.plankSec),
                        avgSteps = max(b.avgSteps, r.avgSteps),
                    ),
                ),
            )
            players.replaceCustomStats(profile.customStats)
        }
    }

    /** Results from the on-device AI's reading of the Player. Only ever nudges; the rails still apply. */
    data class AiAssessment(
        val summary: String,
        val stats: Map<StatType, Int>,
        val limitations: Limitations?,
        val baselines: Baselines?,
        val title: String?,
    )

    suspend fun applyAiAssessment(a: AiAssessment) = lock.withLock {
        var p = players.get() ?: return@withLock
        if (a.summary.isNotBlank() && SafetyLimits.isSafeText(a.summary)) p = p.copy(assessment = a.summary.take(1200))
        // The AI may only move a starting stat a few points from the measured seed, and only before level 2.
        if (p.level == 1 && p.freePoints == 0) {
            for ((s, v) in a.stats) {
                val cur = Progression.stat(p, s)
                p = Progression.withStat(p, s, v.coerceIn(cur - 4, cur + 6).coerceIn(5, 40))
            }
            p = Progression.fullRestore(p)
        }
        a.limitations?.let { ai ->
            // Union: the AI can add care the parser missed, never remove care the Player asked for.
            val l = p.limitations
            p = p.copy(
                limitations = Limitations(
                    noJumping = l.noJumping || ai.noJumping,
                    kneeCare = l.kneeCare || ai.kneeCare,
                    backCare = l.backCare || ai.backCare,
                    upperBodyCare = l.upperBodyCare || ai.upperBodyCare,
                    cardioCare = l.cardioCare || ai.cardioCare,
                    notes = (l.notes + ai.notes.filter { SafetyLimits.isSafeText(it) }).distinct().take(8),
                ),
            )
        }
        a.baselines?.let { ai ->
            val b = p.baselines
            if (b.assessedDay < 0) {
                p = p.copy(
                    baselines = b.copy(
                        pushups = if (b.pushups == 0) ai.pushups.coerceIn(0, 100) else b.pushups,
                        squats = if (b.squats == 0) ai.squats.coerceIn(0, 150) else b.squats,
                        situps = if (b.situps == 0) ai.situps.coerceIn(0, 100) else b.situps,
                        plankSec = if (b.plankSec == 0) ai.plankSec.coerceIn(0, 600) else b.plankSec,
                        avgSteps = if (b.avgSteps == 0) ai.avgSteps.coerceIn(0, 25000) else b.avgSteps,
                    ),
                )
            }
        }
        players.save(p)
    }

    // ---- The daily cycle -----------------------------------------------------------------------

    /** Settle every unsettled day, expire windows, and make sure today has a quest. Idempotent. */
    suspend fun settle(): List<SystemEvent> {
        val out = lock.withLock { settleLocked() }
        announce(out)
        return out
    }

    private suspend fun settleLocked(): List<SystemEvent> {
        var p = players.get() ?: return emptyList()
        val now = time.now()
        val today = time.today()
        val out = mutableListOf<SystemEvent>()

        // 1. Expired penalty windows and trials.
        for (q in quests.active()) {
            if (q.quest.kind == QuestKind.DAILY || q.quest.deadline >= now) continue
            if (q.allDone) {
                p = completeLocked(p, q, today, now, out)
                continue
            }
            quests.update(q.quest.copy(status = QuestStatus.FAILED, completedAt = now))
            when (q.quest.kind) {
                QuestKind.PENALTY -> {
                    val o = PenaltyEngine.failPenalty(p, today)
                    p = o.player
                    out += SystemEvent(
                        SystemEvent.Type.PENALTY_FAILED, "Penalty Quest Failed",
                        "You did not survive the Penalty Zone. HP -${o.hpLost}. Debuff applied: Weakened (XP -25% for ${PenaltyEngine.WEAKENED_DAYS} days).",
                        "You have failed to survive. A debuff has been applied. Weakened.",
                    )
                    if (o.died) out += deathEvent(p)
                }
                QuestKind.TRIAL -> {
                    p = p.copy(trialCooldownUntilDay = today + 2)
                    out += SystemEvent(
                        SystemEvent.Type.WARNING, "Trial Failed",
                        "The Rank-Up Trial has ended in failure. You may retry in 3 days.",
                        "The trial has ended in failure. You are not yet ready.",
                    )
                }
                QuestKind.DAILY -> Unit
            }
        }

        // 2. Every past day, in order, exactly once.
        var missing = 0
        var day = max(p.lastSettledDay + 1, p.awakenedDay)
        var failedAny = false
        var worstCompletion = 1.0
        while (day < today) {
            val q = quests.forDay(day, QuestKind.DAILY)
            if (q == null) {
                missing++
            } else if (q.quest.status == QuestStatus.ACTIVE) {
                val r = settleDayLocked(p, q, day, now, out)
                p = r.first
                if (r.second != null) {
                    failedAny = true
                    worstCompletion = minOf(worstCompletion, r.second!!)
                }
            }
            day++
        }
        if (missing > 0 && !failedAny && p.recoveryDay < today - 1) {
            // Whole days with no quest at all mean the System was not running. One absence penalty,
            // at half weight, no matter how long the gap — the point is to come back, not to be buried.
            val o = PenaltyEngine.failDaily(p, 0.5)
            p = o.player
            failedAny = true
            worstCompletion = 0.5
            out += SystemEvent(
                SystemEvent.Type.PENALTY_STARTED, "Absence Detected",
                "The System was unable to reach you for $missing day(s). HP -${o.hpLost}, XP -${o.xpLost}. Streak reset.",
                "You were gone. The System noticed.",
            )
            if (o.died) out += deathEvent(p)
        }
        if (failedAny && quests.active().none { it.quest.kind == QuestKind.PENALTY }) {
            val plan = QuestPlanner.penalty(p, worstCompletion)
            val start = penaltyWindowStart(now)
            insertPlan(plan, time.dayOf(start), start, start + PenaltyEngine.PENALTY_WINDOW_HOURS * 3_600_000L)
            out += SystemEvent(
                SystemEvent.Type.PENALTY_STARTED, "Penalty Zone",
                "You have failed to complete the Daily Quest. The Penalty Quest \"Survival\" " +
                    "opens at ${hhmm(start)} and lasts ${PenaltyEngine.PENALTY_WINDOW_HOURS} hours.",
                "You have failed to complete the daily quest. You will now be transported to the penalty zone.",
            )
        }
        p = p.copy(lastSettledDay = max(p.lastSettledDay, today - 1))

        // 3. Today's quest.
        if (quests.forDay(today, QuestKind.DAILY) == null) {
            val plan = QuestPlanner.daily(context(p))
            insertPlan(plan, today, time.startOfDay(today), time.endOfDay(today))
            out += questArrived(plan)
        }
        p = grantTitles(p, false, out)
        players.save(p)
        return out
    }

    /** Returns the updated player and, if the day failed, its completion. */
    private suspend fun settleDayLocked(
        p0: PlayerEntity, q: QuestWithObjectives, day: Long, now: Long, out: MutableList<SystemEvent>,
    ): Pair<PlayerEntity, Double?> {
        var p = p0
        val completion = q.completion.toDouble()
        val log = logs.day(day) ?: DayLogEntity(day)
        p = p.copy(fatigue = nextFatigue(p.fatigue, completion, log.sleepMin))
        if (q.allDone) return completeLocked(p, q, day, now, out) to null
        if (p.recoveryDay == day) {
            quests.update(q.quest.copy(status = QuestStatus.EXCUSED, completedAt = now))
            logs.upsert(log.copy(completion = completion.toFloat(), result = "RECOVERY"))
            return Progression.heal(p, 0.2, 0.2) to null
        }
        quests.update(q.quest.copy(status = QuestStatus.FAILED, completedAt = now))
        val o = PenaltyEngine.failDaily(p, completion)
        p = o.player
        logs.upsert(log.copy(completion = completion.toFloat(), result = "FAILED", xpLost = o.xpLost, hpLost = o.hpLost))
        out += SystemEvent(
            SystemEvent.Type.WARNING, "Daily Quest Failed",
            "Daily Quest incomplete (${(completion * 100).roundToInt()}%). HP -${o.hpLost}, XP -${o.xpLost}. Streak reset.",
            "Daily quest incomplete. A penalty will be applied.",
        )
        if (o.died) out += deathEvent(p)
        return p to completion
    }

    private fun nextFatigue(current: Int, completion: Double, sleepMin: Int): Int {
        var f = current - 25 + (completion * 35).roundToInt()
        if (sleepMin >= 420) f -= 15
        if (sleepMin in 1 until 300) f += 15
        return f.coerceIn(0, 100)
    }

    private fun penaltyWindowStart(now: Long): Long {
        val day = time.dayOf(now)
        val hour = time.hourOf(now)
        return when {
            hour < PenaltyEngine.PENALTY_EARLIEST_HOUR -> time.at(day, PenaltyEngine.PENALTY_EARLIEST_HOUR)
            // Keep the window inside one calendar day: a late failure opens the zone tomorrow morning.
            hour >= 24 - PenaltyEngine.PENALTY_WINDOW_HOURS.toInt() -> time.at(day + 1, PenaltyEngine.PENALTY_EARLIEST_HOUR)
            else -> now
        }
    }

    private fun hhmm(millis: Long): String {
        val t = java.time.Instant.ofEpochMilli(millis).atZone(time.zone())
        val sameDay = time.dayOf(millis) == time.today()
        return "%s%02d:%02d".format(if (sameDay) "" else "tomorrow ", t.hour, t.minute)
    }

    private fun deathEvent(p: PlayerEntity) = SystemEvent(
        SystemEvent.Type.DEATH, "You Have Died",
        "HP reached 0. You have been reborn at level ${p.level}. Your streak is gone. Rise again.",
        "You have died. Rise again.",
    )

    private fun questArrived(plan: QuestPlan) = SystemEvent(
        SystemEvent.Type.QUEST_ARRIVED, "Daily Quest Has Arrived",
        "${plan.title}\n" + plan.objectives.joinToString("\n") { "• ${it.type.label}: ${it.type.format(it.target)} ${it.type.unit.takeIf { u -> u == "reps" } ?: ""}".trimEnd() },
        "The daily quest has arrived. ${plan.flavor}",
    )

    private suspend fun insertPlan(plan: QuestPlan, day: Long, startsAt: Long, deadline: Long, rank: Rank? = null): Long {
        val q = QuestEntity(
            day = day, kind = plan.kind, title = plan.title, flavor = plan.flavor, source = plan.source,
            xpReward = plan.xpReward, createdAt = time.now(), startsAt = startsAt, deadline = deadline, targetRank = rank,
        )
        return quests.insertWithObjectives(q, plan.objectives.map { ObjectiveEntity(questId = 0, type = it.type, target = it.target) })
    }

    suspend fun context(p: PlayerEntity): QuestPlanner.Context {
        val today = time.today()
        val days = logs.since(today - 7).filter { it.day < today }
        val avg = days.filter { it.steps > 0 }.map { it.steps }.average().let { if (it.isNaN()) 0 else it.roundToInt() }
        val recent = quests.since(today - 7).filter { it.quest.kind == QuestKind.DAILY && it.quest.day < today }
        val prev = recent.flatMap { it.objectives }.groupBy { it.type }.mapValues { (_, v) -> v.maxOf { it.target } }
        val settled = recent.filter { it.quest.status != QuestStatus.ACTIVE && it.quest.status != QuestStatus.EXCUSED }
        val rate = if (settled.isEmpty()) 0.6 else settled.count { it.quest.status == QuestStatus.COMPLETED }.toDouble() / settled.size
        return QuestPlanner.Context(p, avg, prev, rate, time.dayOfWeek(today))
    }

    suspend fun power(): RankEngine.Power? {
        val p = players.get() ?: return null
        val today = time.today()
        val days = logs.since(today - 7).filter { it.day < today && it.steps > 0 }
        val avg = if (days.isEmpty()) max(p.baselines.avgSteps, logs.day(today)?.steps ?: 0) else days.map { it.steps }.average().roundToInt()
        val recent = quests.since(today - 14).filter { it.quest.kind == QuestKind.DAILY && it.quest.status in listOf(QuestStatus.COMPLETED, QuestStatus.FAILED) }
        val rate = if (recent.isEmpty()) 0.0 else recent.count { it.quest.status == QuestStatus.COMPLETED }.toDouble() / recent.size
        return RankEngine.power(RankEngine.Inputs(p, avg, rate))
    }

    // ---- Progress ------------------------------------------------------------------------------

    /**
     * Absolute totals for today from the step tracker / Health Connect. Monotonic: a lower reading
     * (a sensor reset, a late sync) never takes progress away.
     */
    suspend fun recordActivity(steps: Int, distanceM: Int?, briskMin: Int?) {
        val out = lock.withLock {
            val p = players.get() ?: return@withLock emptyList()
            val today = time.today()
            val now = time.now()
            val log = logs.day(today) ?: DayLogEntity(today)
            val dist = distanceM ?: BodyMetrics.stepsToMeters(steps, p.heightCm)
            val newLog = log.copy(
                steps = max(log.steps, steps),
                distanceM = max(log.distanceM, dist),
                briskMin = max(log.briskMin, briskMin ?: 0),
            )
            logs.upsert(newLog)
            updateObjectivesLocked(p, today, now) { q, o ->
                when (o.type) {
                    ObjectiveType.STEPS -> if (q.quest.kind == QuestKind.PENALTY) {
                        if (now < q.quest.startsAt || time.dayOf(q.quest.startsAt) != today) o
                        else if (o.baseline < 0) o.copy(baseline = newLog.steps)
                        else o.copy(progress = max(o.progress, newLog.steps - o.baseline))
                    } else o.copy(progress = max(o.progress, newLog.steps))
                    ObjectiveType.DISTANCE_M -> o.copy(progress = max(o.progress, newLog.distanceM))
                    ObjectiveType.BRISK_MIN -> o.copy(progress = max(o.progress, newLog.briskMin))
                    else -> o
                }
            }
        }
        announce(out)
    }

    suspend fun recordSleep(minutes: Int) {
        val out = lock.withLock {
            val p = players.get() ?: return@withLock emptyList()
            val today = time.today()
            val log = logs.day(today) ?: DayLogEntity(today)
            logs.upsert(log.copy(sleepMin = max(log.sleepMin, minutes)))
            updateObjectivesLocked(p, today, time.now()) { _, o ->
                if (o.type == ObjectiveType.SLEEP_MIN) o.copy(progress = max(o.progress, minutes)) else o
            }
        }
        announce(out)
    }

    /**
     * A finished set: reps, plank seconds or meditation minutes. [verified] = counted by a sensor
     * or the camera rather than typed in.
     */
    suspend fun addSet(type: ObjectiveType, amount: Int, verified: Boolean) {
        if (amount <= 0) return
        val out = lock.withLock {
            var p = players.get() ?: return@withLock emptyList()
            val today = time.today()
            val extra = mutableListOf<SystemEvent>()
            if (verified) {
                val b = p.baselines
                val nb = when (type) {
                    ObjectiveType.PUSHUPS -> if (amount > b.pushups) b.copy(pushups = amount) else null
                    ObjectiveType.PLANK_SEC -> if (amount > b.plankSec) b.copy(plankSec = amount) else null
                    else -> null
                }
                if (nb != null && b.assessedDay >= 0) {
                    extra += SystemEvent(SystemEvent.Type.INFO, "Record Updated", "New personal record: ${type.label} ${type.format(amount)}.", "New record.")
                }
                if (nb != null) p = p.copy(baselines = nb)
            }
            if (type == ObjectiveType.MEDITATE_MIN) p = p.copy(mp = (p.mp + amount * 2).coerceAtMost(Progression.maxMp(p)))
            players.save(p)
            extra + updateObjectivesLocked(p, today, time.now()) { q, o ->
                if (o.type != type || q.quest.kind == QuestKind.PENALTY) o
                else o.copy(progress = o.progress + amount, unverified = o.unverified + if (verified) 0 else amount)
            }
        }
        announce(out)
    }

    private suspend fun updateObjectivesLocked(
        p0: PlayerEntity, today: Long, now: Long,
        change: (QuestWithObjectives, ObjectiveEntity) -> ObjectiveEntity,
    ): List<SystemEvent> {
        var p = p0
        val out = mutableListOf<SystemEvent>()
        for (q in quests.active()) {
            val relevant = when (q.quest.kind) {
                QuestKind.DAILY, QuestKind.TRIAL -> q.quest.day == today
                QuestKind.PENALTY -> now <= q.quest.deadline
            }
            if (!relevant) continue
            val before = q.completion
            val updated = q.objectives.map { o -> change(q, o).also { if (it != o) quests.updateObjective(it) } }
            val nq = q.copy(objectives = updated)
            if (nq.allDone) {
                p = completeLocked(p, nq, today, now, out)
            } else if (q.quest.kind == QuestKind.DAILY) {
                val after = nq.completion
                listOf(0.25f, 0.5f, 0.75f).firstOrNull { before < it && after >= it }?.let { mark ->
                    val pct = (mark * 100).roundToInt()
                    out += SystemEvent(SystemEvent.Type.QUEST_PROGRESS, "Quest Progress", "Daily Quest $pct% complete.", "Daily quest, $pct percent complete.")
                }
            }
        }
        players.save(p)
        return out
    }

    private suspend fun completeLocked(
        p0: PlayerEntity, q: QuestWithObjectives, today: Long, now: Long, out: MutableList<SystemEvent>,
    ): PlayerEntity {
        var p = p0
        quests.update(q.quest.copy(status = QuestStatus.COMPLETED, completedAt = now))
        val unverifiedShare = q.objectives.map { if (it.progress <= 0) 0.0 else it.unverified.toDouble() / it.progress }.average()
        val exceeded = q.objectives.all { it.progress >= it.target * 1.5 }
        val base = q.quest.xpReward * (1.0 - 0.5 * unverifiedShare) * (if (exceeded) 1.25 else 1.0)
        val xp = Progression.addXp(p, base.roundToInt(), today)
        p = xp.player
        var clearedPenalty = false
        when (q.quest.kind) {
            QuestKind.DAILY -> {
                val streak = p.streak + 1
                p = Progression.fullRestore(
                    p.copy(
                        streak = streak, bestStreak = max(p.bestStreak, streak), questsCompleted = p.questsCompleted + 1,
                        freePoints = p.freePoints + if (streak % 7 == 0) 2 else 0,
                    ),
                )
                val log = logs.day(q.quest.day) ?: DayLogEntity(q.quest.day)
                logs.upsert(log.copy(completion = 1f, result = "CLEARED", xpGained = log.xpGained + xp.gained))
                out += SystemEvent(
                    SystemEvent.Type.QUEST_COMPLETED, "Daily Quest Complete",
                    "Rewards: XP +${xp.gained}, full recovery. Streak: $streak day(s)." +
                        (if (exceeded) " Bonus: every goal exceeded by half." else "") +
                        (if (streak % 7 == 0) " Streak reward: +2 ability points." else ""),
                    "You have completed the daily quest. Rewards have been distributed.",
                )
            }
            QuestKind.PENALTY -> {
                clearedPenalty = true
                out += SystemEvent(
                    SystemEvent.Type.PENALTY_CLEARED, "Survived",
                    "You have survived the Penalty Zone. XP +${xp.gained}.",
                    "You have survived the penalty zone.",
                )
            }
            QuestKind.TRIAL -> {
                val rank = q.quest.targetRank ?: p.rank.next ?: p.rank
                p = p.copy(rank = rank, job = jobFor(rank, p.job))
                out += SystemEvent(
                    SystemEvent.Type.RANK_UP, "Rank Up",
                    "Re-evaluation complete. You are now a ${rank.displayName} Hunter: ${rank.epithet}. XP +${xp.gained}.",
                    "Re-evaluation complete. You are now ${rank.displayName}.",
                )
            }
        }
        if (xp.levelsGained > 0) {
            out += SystemEvent(
                SystemEvent.Type.LEVEL_UP, "Level Up!",
                "You have leveled up! Level ${p.level}. +${xp.levelsGained * Progression.POINTS_PER_LEVEL} ability points.",
                "You have leveled up.",
            )
        }
        p = grantTitles(p, clearedPenalty, out)
        return p
    }

    private fun jobFor(rank: Rank, current: String): String = when (rank) {
        Rank.E, Rank.D, Rank.C -> current
        Rank.B -> "Hunter"
        Rank.A, Rank.S -> "Necromancer"
        Rank.NATIONAL -> "Shadow Monarch"
    }

    private fun grantTitles(p0: PlayerEntity, clearedPenalty: Boolean, out: MutableList<SystemEvent>): PlayerEntity {
        var p = p0
        for (t in Titles.newlyEarned(p, clearedPenalty)) {
            p = p.copy(titles = p.titles + t.name, title = if (p.title == "None") t.name else p.title)
            out += SystemEvent(SystemEvent.Type.TITLE, "Title Acquired", "You have acquired the title \"${t.name}\". ${t.description}", "You have acquired a title. ${t.name}.")
        }
        return p
    }

    // ---- Player actions -----------------------------------------------------------------------

    suspend fun allocate(stat: StatType): Boolean = lock.withLock {
        val p = players.get() ?: return@withLock false
        val next = Progression.allocate(p, stat) ?: return@withLock false
        players.save(next)
        true
    }

    suspend fun equipTitle(name: String) = lock.withLock {
        val p = players.get() ?: return@withLock
        if (name in p.titles || name == "None") players.save(p.copy(title = name))
    }

    /** Sick or resting: today's quest is excused and any open penalty is pushed to tomorrow. */
    suspend fun declareRecovery(): Boolean {
        val out = lock.withLock {
            val p = players.get() ?: return@withLock null
            val today = time.today()
            val next = PenaltyEngine.declareRecovery(p, today) ?: return@withLock null
            players.save(next)
            for (q in quests.active().filter { it.quest.kind == QuestKind.PENALTY }) {
                val start = time.at(today + 1, PenaltyEngine.PENALTY_EARLIEST_HOUR)
                quests.update(q.quest.copy(day = today + 1, startsAt = start, deadline = start + PenaltyEngine.PENALTY_WINDOW_HOURS * 3_600_000L))
                q.objectives.forEach { quests.updateObjective(it.copy(progress = 0, baseline = -1)) }
            }
            listOf(
                SystemEvent(
                    SystemEvent.Type.INFO, "Recovery Status",
                    "Recovery registered. Today's quest will not be penalised. ${PenaltyEngine.recoveryLeft(next, today)} recovery day(s) left this month.",
                    "Recovery status registered. Rest well, Player.",
                ),
            )
        } ?: return false
        announce(out)
        return true
    }

    /** Start the Rank-Up Trial. [aiPlan] (already enveloped by the director) replaces the rules trial when given. */
    suspend fun acceptTrial(aiPlan: QuestPlan? = null): Boolean {
        val out = lock.withLock {
            val p = players.get() ?: return@withLock null
            val today = time.today()
            val power = powerLocked(p) ?: return@withLock null
            val rank = RankEngine.trialAvailable(p, power, today) ?: return@withLock null
            if (quests.active().any { it.quest.kind == QuestKind.TRIAL }) return@withLock null
            // Before noon the trial is today; after, it's tomorrow — a full day, never a rushed evening.
            val day = if (time.hourOf(time.now()) < 12) today else today + 1
            val rules = QuestPlanner.trial(context(p), rank)
            val plan = aiPlan?.takeIf { it.kind == QuestKind.TRIAL && it.objectives.isNotEmpty() }?.let { ai ->
                ai.copy(objectives = ai.objectives.map { it.copy(target = it.target.coerceIn(1, SafetyLimits.cap(it.type, p).coerceAtLeast(1))) })
            } ?: rules
            insertPlan(plan, day, time.startOfDay(day), time.endOfDay(day), rank)
            listOf(
                SystemEvent(
                    SystemEvent.Type.RANK_TRIAL, "Rank-Up Trial Accepted",
                    "${plan.title} — ${if (day == today) "today" else "tomorrow"}. Complete every objective before midnight.",
                    "The trial has begun. Prove yourself.",
                ),
            )
        } ?: return false
        announce(out)
        return true
    }

    private suspend fun powerLocked(p: PlayerEntity): Int? {
        val today = time.today()
        val days = logs.since(today - 7).filter { it.day < today && it.steps > 0 }
        val avg = if (days.isEmpty()) p.baselines.avgSteps else days.map { it.steps }.average().roundToInt()
        val recent = quests.since(today - 14).filter { it.quest.kind == QuestKind.DAILY && it.quest.status in listOf(QuestStatus.COMPLETED, QuestStatus.FAILED) }
        val rate = if (recent.isEmpty()) 0.0 else recent.count { it.quest.status == QuestStatus.COMPLETED }.toDouble() / recent.size
        return RankEngine.power(RankEngine.Inputs(p, avg, rate)).total
    }

    /** Swap today's quest for a newly written one (AI refinement or a reroll). Progress on shared objective types carries over. */
    suspend fun replaceTodayPlan(plan: QuestPlan, mpCost: Int = 0): Boolean {
        val out = lock.withLock {
            val p = players.get() ?: return@withLock null
            if (p.mp < mpCost) return@withLock null
            val q = quests.forDay(time.today(), QuestKind.DAILY) ?: return@withLock null
            if (q.quest.status != QuestStatus.ACTIVE) return@withLock null
            val repsDone = q.objectives.filter { !(it.type == ObjectiveType.STEPS || it.type == ObjectiveType.DISTANCE_M || it.type == ObjectiveType.BRISK_MIN || it.type == ObjectiveType.SLEEP_MIN) }.sumOf { it.progress }
            if (repsDone > 0 && mpCost == 0) return@withLock null // never rewrite a quest the Player has started working on
            val safe = SafetyLimits.sanitize(plan, p, context(p).previousTargets, q.quest.flavor)
            if (safe.objectives.isEmpty()) return@withLock null
            val old = q.objectives.associateBy { it.type }
            quests.update(q.quest.copy(title = safe.title, flavor = safe.flavor, source = safe.source, xpReward = safe.xpReward))
            quests.replaceObjectives(
                q.quest.id,
                safe.objectives.map { o ->
                    ObjectiveEntity(questId = q.quest.id, type = o.type, target = o.target, progress = old[o.type]?.progress ?: 0, unverified = old[o.type]?.unverified ?: 0)
                },
            )
            if (mpCost > 0) players.save(p.copy(mp = p.mp - mpCost))
            listOf(SystemEvent(SystemEvent.Type.QUEST_ARRIVED, "Quest Updated", safe.title + "\n" + safe.flavor, safe.flavor))
        } ?: return false
        announce(out)
        return true
    }

    /** Rules-only reroll: rotates the accessory objective. Costs MP. */
    suspend fun reroll(): Boolean {
        val p = players.get() ?: return false
        val base = QuestPlanner.daily(context(p))
        val accessories = listOf(ObjectiveType.PLANK_SEC, ObjectiveType.BRISK_MIN, ObjectiveType.MEDITATE_MIN)
        val current = todayQuest()?.objectives?.map { it.type }?.toSet() ?: emptySet()
        val pick = accessories.firstOrNull { it !in current && SafetyLimits.allowed(it, p) } ?: return false
        val plan = base.copy(
            objectives = base.objectives.filter { it.type !in accessories } + ObjectivePlan(pick, SafetyLimits.defaultTargetFor(pick, p)),
            flavor = "The quest has been rewritten at the cost of mana.",
        )
        return replaceTodayPlan(plan, mpCost = 30)
    }

    data class AssessmentResult(val pushups: Int?, val squats: Int?, val situps: Int?, val plankSec: Int?)

    suspend fun completeAssessment(r: AssessmentResult) {
        val out = lock.withLock {
            var p = players.get() ?: return@withLock emptyList()
            val first = p.baselines.assessedDay < 0
            val b = p.baselines
            val nb = b.copy(
                pushups = r.pushups ?: b.pushups,
                squats = r.squats ?: b.squats,
                situps = r.situps ?: b.situps,
                plankSec = r.plankSec ?: b.plankSec,
                assessedDay = time.today(),
            )
            val events = mutableListOf<SystemEvent>()
            if (first) {
                val seeded = Progression.initialStats(p.copy(baselines = nb))
                p = p.copy(
                    baselines = nb,
                    str = max(p.str, seeded.str), agi = max(p.agi, seeded.agi), vit = max(p.vit, seeded.vit),
                )
                p = Progression.fullRestore(p)
                events += SystemEvent(SystemEvent.Type.INFO, "Assessment Complete", "Your combat data has been recorded. Quests will now be calibrated to you.", "Assessment complete. Your data has been recorded.")
            } else {
                // Re-evaluation: real improvement becomes stat growth.
                fun gain(old: Int, new: Int?): Int = if (new == null || old <= 0 || new <= old) 0 else floor((new - old).toDouble() / old / 0.10).toInt().coerceAtMost(5)
                val strGain = gain(b.pushups, r.pushups) + gain(b.squats, r.squats) / 2
                val vitGain = gain(b.situps, r.situps) / 2 + gain(b.plankSec, r.plankSec)
                p = p.copy(baselines = nb, str = p.str + strGain, vit = p.vit + vitGain)
                val msg = if (strGain + vitGain > 0) "Your body has grown. STR +$strGain, VIT +$vitGain." else "No growth detected since the last evaluation."
                events += SystemEvent(SystemEvent.Type.INFO, "Re-evaluation", msg, msg)
            }
            players.save(p)
            events
        }
        announce(out)
    }

    suspend fun addWeight(kg: Double, source: String = "MANUAL", at: Long = time.now()): List<SystemEvent> {
        val out = lock.withLock {
            var p = players.get() ?: return@withLock emptyList()
            if (source != "MANUAL" && logs.weightAt(source, at) != null) return@withLock emptyList()
            val previous = logs.weights()
            val oldMin = (previous.minOfOrNull { it.kg } ?: p.startWeightKg).coerceAtMost(p.startWeightKg)
            logs.insertWeight(WeightEntity(time = at, kg = kg, source = source))
            val latest = previous.maxOfOrNull { it.time } ?: 0L
            val events = mutableListOf<SystemEvent>()
            if (at >= latest) p = p.copy(weightKg = kg)
            val before = floor(p.startWeightKg - oldMin).toInt()
            val after = floor(p.startWeightKg - kg).toInt()
            if (after > before) {
                val kgs = after - before
                val xp = Progression.addXp(p, 120 * kgs, time.today())
                p = xp.player
                events += SystemEvent(
                    SystemEvent.Type.WEIGHT, "Body Transformation",
                    "You have lost $after kg in total. XP +${xp.gained}.",
                    "Your body is changing. $after kilograms lost.",
                )
                if (xp.levelsGained > 0) events += SystemEvent(SystemEvent.Type.LEVEL_UP, "Level Up!", "You have leveled up! Level ${p.level}.", "You have leveled up.")
            }
            p = grantTitles(p, false, events)
            players.save(p)
            events
        }
        announce(out)
        return out
    }

    // ---- Events --------------------------------------------------------------------------------

    private suspend fun announce(events: List<SystemEvent>) {
        if (events.isEmpty()) return
        val now = time.now()
        for (e in events) {
            logs.insertEvent(EventEntity(time = now, type = e.type.name, title = e.title, message = e.message))
            _events.emit(e)
        }
    }

    /** An announcement that changes no state (reminders, warnings): logged and broadcast like any other. */
    suspend fun broadcast(e: SystemEvent) = announce(listOf(e))

    /** Test/debug hook: shift the game clock. */
    fun shiftTime(millis: Long) {
        time.offsetMillis += millis
    }
}
