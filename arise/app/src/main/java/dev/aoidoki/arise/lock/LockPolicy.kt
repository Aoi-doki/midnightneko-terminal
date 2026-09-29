package dev.aoidoki.arise.lock

import dev.aoidoki.arise.data.LockSettings
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus

/** What the lock is doing right now. [quest] is null during a test lock. */
data class LockState(val engaged: Boolean, val quest: QuestWithObjectives? = null, val test: Boolean = false) {
    companion object {
        val Off = LockState(false)
    }
}

/**
 * When the phone is locked, and which apps stay usable. Pure, so it can be tested without Android.
 *
 * The lock only exists inside an open Penalty Quest window: it lifts by itself the moment the steps
 * are walked, when the window closes, or once the override code is used for that quest.
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

    fun state(settings: LockSettings, active: List<QuestWithObjectives>, now: Long): LockState {
        if (!settings.armed) return LockState.Off
        if (now < settings.testUntil) return LockState(engaged = true, test = true)
        val q = active.firstOrNull {
            it.quest.kind == QuestKind.PENALTY &&
                it.quest.status == QuestStatus.ACTIVE &&
                now >= it.quest.startsAt && now < it.quest.deadline &&
                it.quest.id != settings.overriddenQuestId
        } ?: return LockState.Off
        return LockState(engaged = true, quest = q)
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
