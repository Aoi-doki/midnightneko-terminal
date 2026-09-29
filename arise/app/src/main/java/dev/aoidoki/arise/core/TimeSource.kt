package dev.aoidoki.arise.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wall-clock time for the game. Everything that cares about "today" asks this, so tests (and the
 * emulator smoke test) can move time forward to force a midnight.
 */
open class TimeSource {
    @Volatile
    var offsetMillis: Long = 0

    open fun now(): Long = System.currentTimeMillis() + offsetMillis

    open fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): Long = dayOf(now())

    fun dayOf(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toEpochDay()

    fun startOfDay(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun endOfDay(day: Long): Long = startOfDay(day + 1) - 1

    fun at(day: Long, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.ofEpochDay(day), java.time.LocalTime.of(hour, minute)).atZone(zone()).toInstant().toEpochMilli()

    fun hourOf(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone()).hour

    fun dayOfWeek(day: Long): Int = LocalDate.ofEpochDay(day).dayOfWeek.value
}
