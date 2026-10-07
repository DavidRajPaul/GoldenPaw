package com.goldenpaw.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Locale-light formatting that works identically on Android, iOS and Desktop. */
object Fmt {
    private val monthShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val monthLong = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    fun monthShort(month: Month): String = monthShort[month.ordinal]
    fun monthLong(month: Month): String = monthLong[month.ordinal]

    /** "6 Oct" */
    fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} ${monthShort(date.month)}"

    /** "6 Oct 2026" */
    fun dayMonthYear(date: LocalDate): String = "${date.dayOfMonth} ${monthShort(date.month)} ${date.year}"

    /** "Tuesday, 6 October" */
    fun weekdayDayMonth(date: LocalDate): String =
        "${date.dayOfWeek.longLabel()}, ${date.dayOfMonth} ${monthLong(date.month)}"

    /** "Tue 6 Oct" */
    fun shortWeekday(date: LocalDate): String = "${date.dayOfWeek.shortLabel()} ${date.dayOfMonth} ${monthShort(date.month)}"

    /** "Oct 2026" */
    fun monthYear(date: LocalDate): String = "${monthShort(date.month)} ${date.year}"

    fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> shortWeekday(date)
    }

    fun time(instant: Instant, zone: TimeZone): String = instant.toLocalTime(zone).format12h()

    /** "6–12 Oct" or "29 Sep – 5 Oct" */
    fun range(from: LocalDate, to: LocalDate): String = when {
        from.year != to.year -> "${dayMonthYear(from)} – ${dayMonthYear(to)}"
        from.month != to.month -> "${dayMonth(from)} – ${dayMonth(to)}"
        else -> "${from.dayOfMonth}–${to.dayOfMonth} ${monthShort(to.month)}"
    }

    /** "2 min ago", "3 h ago", "yesterday", or a date. */
    fun ago(instant: Instant, now: Instant, zone: TimeZone): String {
        val minutes = (now - instant).inWholeMinutes
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 && instant.toLocalDate(zone) == now.toLocalDate(zone) -> "${minutes / 60} h ago"
            instant.toLocalDate(zone) == now.toLocalDate(zone).minusDays(1) -> "yesterday"
            else -> dayMonth(instant.toLocalDate(zone))
        }
    }
}

/** Fixed-decimal formatting without String.format (not available in common Kotlin). */
fun Double.toFixed(decimals: Int): String {
    if (isNaN()) return "–"
    val factor = 10.0.pow(decimals)
    val scaled = (abs(this) * factor).roundToLong()
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    val intPart = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
    return "$sign$intPart.$frac"
}

fun Float.toFixed(decimals: Int): String = toDouble().toFixed(decimals)

/** "+3.2%" / "-1.0%" */
fun Double.signedPercent(decimals: Int = 1): String = (if (this >= 0) "+" else "") + toFixed(decimals) + "%"

/** Drops a trailing ".0": 30.0 → "30", 2.5 → "2.5". */
fun Double.compact(): String = if (this % 1.0 == 0.0) toLong().toString() else toFixed(1)

fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String =
    "$count ${if (count == 1) singular else pluralForm}"
