package com.goldenpaw.core

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Injectable time source. Domain logic never reads the system clock directly, which keeps
 * schedule expansion, streaks and weekly digests deterministic in tests.
 */
interface AppClock {
    fun now(): Instant
    fun zone(): TimeZone
}

object SystemAppClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override fun zone(): TimeZone = TimeZone.currentSystemDefault()
}

/** Fixed clock for tests and previews. */
class FixedAppClock(var instant: Instant, private val timeZone: TimeZone = TimeZone.UTC) : AppClock {
    override fun now(): Instant = instant
    override fun zone(): TimeZone = timeZone
}

fun AppClock.today(): LocalDate = now().toLocalDateTime(zone()).date
fun AppClock.localTime(): LocalTime = now().toLocalDateTime(zone()).time

// ------------------------------------------------------------------ LocalDate helpers

fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)
fun LocalDate.minusDays(days: Int): LocalDate = minus(days, DateTimeUnit.DAY)
fun LocalDate.plusDays(days: Long): LocalDate = plusDays(days.toInt())
fun LocalDate.minusDays(days: Long): LocalDate = minusDays(days.toInt())
fun LocalDate.plusMonths(months: Int): LocalDate = plus(months, DateTimeUnit.MONTH)
fun LocalDate.minusMonths(months: Int): LocalDate = minus(months, DateTimeUnit.MONTH)
fun LocalDate.minusYears(years: Int): LocalDate = minus(years, DateTimeUnit.YEAR)

fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
fun LocalDate.isAfter(other: LocalDate): Boolean = this > other

/** Days since 1970-01-01 (matches java.time's epochDay, which is what the v1 database stored). */
fun LocalDate.epochDay(): Long = toEpochDays().toLong()
fun localDateOfEpochDay(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())

fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)

/** Monday of this date's ISO week. */
fun LocalDate.startOfWeek(): LocalDate = minusDays(dayOfWeek.isoDayNumber - 1)

/** Sunday of this date's ISO week. */
fun LocalDate.endOfWeek(): LocalDate = startOfWeek().plusDays(6)

fun LocalDate.startOfDay(zone: TimeZone): Instant = atStartOfDayIn(zone)

/** Wall-clock [time] on this date. Times inside a DST gap shift forward (least surprising for reminders). */
fun LocalDate.at(time: LocalTime, zone: TimeZone): Instant = LocalDateTime(this, time).toInstant(zone)

fun dateRange(from: LocalDate, toInclusive: LocalDate): Sequence<LocalDate> =
    generateSequence(from) { it.plusDays(1) }.takeWhile { it <= toInclusive }

// ------------------------------------------------------------------ Instant / LocalTime helpers

fun Instant.toLocalDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date
fun Instant.toLocalTime(zone: TimeZone): LocalTime = toLocalDateTime(zone).time

/** Truncates to the start of the minute (used for as-needed dose keys). */
fun Instant.truncatedToMinute(): Instant =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() - toEpochMilliseconds().mod(60_000L))

fun LocalTime.plusHours(hours: Int): LocalTime {
    val total = ((hour + hours) % 24 + 24) % 24
    return LocalTime(total, minute)
}

val LocalTime.minutesOfDay: Int get() = hour * 60 + minute

fun localTimeOfMinutes(minutes: Int): LocalTime {
    val m = ((minutes % 1440) + 1440) % 1440
    return LocalTime(m / 60, m % 60)
}

fun LocalTime.hhmm(): String = "${hour.pad2()}:${minute.pad2()}"

/** "8:05 AM" */
fun LocalTime.format12h(): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h:${minute.pad2()} $suffix"
}

fun DayOfWeek.shortLabel(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
fun DayOfWeek.longLabel(): String = name.lowercase().replaceFirstChar { it.uppercase() }

internal fun Int.pad2(): String = if (this < 10) "0$this" else toString()
