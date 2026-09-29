package dev.aoidoki.arise.lock

import dev.aoidoki.arise.data.LockSettings
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the lock is doing right now. [quest] is set during a penalty; [night] during the Night Lock,
 * with [until] the end of tonight's window.
 */
data class LockState(
    val engaged: Boolean,
    val quest: QuestWithObjectives? = null,
    val test: Boolean = false,
    val night: Boolean = false,
    val until: Long = 0,
) {
    companion object {
        val Off = LockState(false)
    }
}

/**
 * When the phone is locked, and which apps stay usable. Pure, so it can be tested without Android.
 *
 * The Penalty Lock exists only inside an open Penalty Quest window: it lifts by itself the moment
 * the steps are walked, when the window closes, or once the override code is used for that quest.
 * The Night Lock covers a daily window, and the override lifts it until morning.
 */
object LockPolicy {
    /** Apps that are never blocked: calls, emergency, alarms. Launchers and Settings are not here on purpose. */
    val ESSENTIAL = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
    )

    /** Windows that float over the foreground app (shade, keyboard) and say nothing about what's in use. */
    val TRANSIENT = setOf("com.android.systemui", "android")

    /** Test lock first, then an open penalty window, then the Night Lock. */
    fun state(settings: LockSettings, active: List<QuestWithObjectives>, now: Long, zone: ZoneId): LockState {
        if (!settings.armed) return LockState.Off
        if (now < settings.testUntil) return LockState(engaged = true, test = true)
        if (settings.enabled) {
            val q = active.firstOrNull {
                it.quest.kind == QuestKind.PENALTY &&
                    it.quest.status == QuestStatus.ACTIVE &&
                    now >= it.quest.startsAt && now < it.quest.deadline &&
                    it.quest.id != settings.overriddenQuestId
            }
            if (q != null) return LockState(engaged = true, quest = q)
        }
        if (settings.nightEnabled && now >= settings.nightLiftedUntil) {
            val w = nightWindow(now, zone, settings.nightStart, settings.nightEnd)
            if (w != null) return LockState(engaged = true, night = true, until = w.last + 1)
        }
        return LockState.Off
    }

    /**
     * The night window containing [now], as `[start, end)` in epoch millis, or null if [now] is
     * outside it. [start] and [end] are minutes after midnight; a window may cross midnight.
     */
    fun nightWindow(now: Long, zone: ZoneId, start: Int, end: Int): LongRange? {
        if (start == end) return null
        val here = Instant.ofEpochMilli(now).atZone(zone)
        val today = here.toLocalDate()
        fun at(date: LocalDate, minutes: Int) = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
        // The window can have started today or (when it crosses midnight) yesterday.
        for (day in listOf(today, today.minusDays(1))) {
            val s = at(day, start)
            val e = if (end > start) at(day, end) else at(day.plusDays(1), end)
            if (now in s until e) return s until e
        }
        return null
    }

    fun isEssential(pkg: String): Boolean = pkg in ESSENTIAL || pkg.startsWith("com.samsung.android.emergency")

    /**
     * Whether [pkg] may be used while locked. [own] is SYSTEM itself (the penalty screen lives
     * there), except during a test lock, when the overlay should show straight away.
     */
    fun isAllowed(pkg: String, own: String, extra: Set<String>, allow: Set<String>, test: Boolean): Boolean = when {
        pkg == own -> !test
        isEssential(pkg) -> true
        pkg in extra -> true
        else -> pkg in allow
    }
}
