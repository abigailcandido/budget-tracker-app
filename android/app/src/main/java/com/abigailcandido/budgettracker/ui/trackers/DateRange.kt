package com.abigailcandido.budgettracker.ui.trackers

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** "25 Oct 2026". */
fun LocalDate.asDayLabel(): String = format(DAY_MONTH_YEAR)

/** How a range was chosen, which decides how far the arrows step. */
enum class RangeKind { Week, Month, Year, Custom }

/** A span of days, both ends included. */
data class DateRange(
    val start: LocalDate,
    val end: LocalDate,
    val kind: RangeKind,
) {
    val label: String
        get() = when (kind) {
            RangeKind.Month -> start.format(MONTH_YEAR)
            RangeKind.Year -> start.year.toString()
            RangeKind.Week, RangeKind.Custom -> spanLabel()
        }

    /** The period just before this one: last week, last month, or the same number of days. */
    fun previous(): DateRange = when (kind) {
        RangeKind.Week -> week(start.minusWeeks(1))
        RangeKind.Month -> month(start.minusMonths(1))
        RangeKind.Year -> year(start.minusYears(1))
        RangeKind.Custom -> {
            val days = lengthInDays()
            DateRange(start.minusDays(days), end.minusDays(days), RangeKind.Custom)
        }
    }

    fun next(): DateRange = when (kind) {
        RangeKind.Week -> week(start.plusWeeks(1))
        RangeKind.Month -> month(start.plusMonths(1))
        RangeKind.Year -> year(start.plusYears(1))
        RangeKind.Custom -> {
            val days = lengthInDays()
            DateRange(start.plusDays(days), end.plusDays(days), RangeKind.Custom)
        }
    }

    private fun lengthInDays(): Long = ChronoUnit.DAYS.between(start, end) + 1

    private fun spanLabel(): String = when {
        start == end -> start.asDayLabel()
        start.year == end.year -> "${start.format(DAY_MONTH)} – ${end.asDayLabel()}"
        else -> "${start.asDayLabel()} – ${end.asDayLabel()}"
    }

    companion object {
        /** Weeks run Monday to Sunday. */
        fun week(day: LocalDate): DateRange {
            val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return DateRange(monday, monday.plusDays(6), RangeKind.Week)
        }

        fun month(day: LocalDate): DateRange = DateRange(
            start = day.withDayOfMonth(1),
            end = day.withDayOfMonth(day.lengthOfMonth()),
            kind = RangeKind.Month,
        )

        fun year(day: LocalDate): DateRange = DateRange(
            start = day.withDayOfYear(1),
            end = day.withDayOfYear(day.lengthOfYear()),
            kind = RangeKind.Year,
        )

        /** Whichever order the two days arrive in, the earlier one comes first. */
        fun custom(first: LocalDate, second: LocalDate): DateRange =
            if (first <= second) {
                DateRange(first, second, RangeKind.Custom)
            } else {
                DateRange(second, first, RangeKind.Custom)
            }
    }
}