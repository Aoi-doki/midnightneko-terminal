package dev.aoidoki.arise.sense

import kotlinx.serialization.Serializable

/**
 * Turns TYPE_STEP_COUNTER readings (a running total since boot) into "steps today", surviving
 * reboots and midnight. Pure, so it's tested without a sensor.
 */
@Serializable
data class StepState(
    val day: Long = -1,
    /** Steps already counted today before the current counter segment. */
    val carried: Int = 0,
    /** Counter value at the start of the current segment (today, since last reboot). -1 = unset. */
    val segmentStart: Long = -1,
    val lastCounter: Long = -1,
    /** Brisk-minute tracking: the minute bucket and the step count at its start. */
    val minuteKey: Long = -1,
    val minuteStartSteps: Int = 0,
    val briskMinutes: Int = 0,
) {
    val today: Int get() = if (segmentStart < 0 || lastCounter < 0) carried else carried + (lastCounter - segmentStart).toInt().coerceAtLeast(0)
}

object StepMath {
    /** Steps per minute that count as brisk movement. */
    const val BRISK_CADENCE = 100

    fun onReading(state: StepState, counter: Long, day: Long, minuteKey: Long): StepState {
        var s = state
        if (s.day != day) {
            // New day: everything counted so far belonged to yesterday.
            s = StepState(day = day, carried = 0, segmentStart = counter, lastCounter = counter, minuteKey = minuteKey, minuteStartSteps = 0, briskMinutes = 0)
            return s
        }
        if (s.segmentStart < 0 || counter < s.lastCounter) {
            // First reading today, or the counter went backwards (reboot): bank what we had, start a new segment.
            s = s.copy(carried = s.today, segmentStart = counter, lastCounter = counter)
        } else {
            s = s.copy(lastCounter = counter)
        }
        return bucket(s, minuteKey)
    }

    private fun bucket(s: StepState, minuteKey: Long): StepState {
        if (s.minuteKey == minuteKey) return s
        val stepsInMinute = s.today - s.minuteStartSteps
        val brisk = if (s.minuteKey >= 0 && minuteKey == s.minuteKey + 1 && stepsInMinute >= BRISK_CADENCE) s.briskMinutes + 1 else s.briskMinutes
        return s.copy(minuteKey = minuteKey, minuteStartSteps = s.today, briskMinutes = brisk)
    }
}
