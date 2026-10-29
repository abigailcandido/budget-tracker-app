package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.ExpectedAmount
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Up to ten digits before the point and two after, which is the most the server stores. */
private val EXPECTED_AMOUNT_PATTERN = Regex("^\\d{0,10}(\\.\\d{0,2})?$")

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/**
 * The dialog that sets one category's expected amount per month on the
 * Budget tab. The amount applies from the chosen month on, until a later
 * change, so the months before keep theirs. It lives in the tracker screen's
 * ViewModel, like the entry form.
 */
data class ExpectedAmountForm(
    val categoryId: Int,
    /** "Groceries" or "Bills › Electricity". */
    val label: String,
    val currency: String,
    /** Every change so far, oldest first. */
    val amounts: List<ExpectedAmount>,
    /** The first month the new amount is for. */
    val month: YearMonth,
    val amount: String,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** The amount already in force in the chosen month, if any. */
    val currentAmount: String?
        get() = amountIn(amounts, month)

    /** A change already set for a later month, where this one stops. */
    val nextChange: ExpectedAmount?
        get() = amounts.firstOrNull { it.month > month }

    fun accepts(value: String): Boolean = EXPECTED_AMOUNT_PATTERN.matches(value)

    val canSave: Boolean
        get() = !isSaving && amount.toBigDecimalOrNull() != null

    /** Clearing only means something when an amount is in force that month. */
    val canClear: Boolean
        get() = !isSaving && currentAmount != null
}

/** The month a change starts from. */
val ExpectedAmount.month: YearMonth
    get() = YearMonth.from(LocalDate.parse(startsOn))

/** "October 2026". */
fun YearMonth.label(): String = format(MONTH_LABEL)

/** The monthly amount in force in a month: the latest change from that month or earlier. */
fun amountIn(amounts: List<ExpectedAmount>, month: YearMonth): String? =
    amounts.lastOrNull { it.month <= month }?.amount

/**
 * The month a change starts from when the dialog opens: the month showing,
 * when the dates are within one month; this month, when the dates span
 * several months and include today; otherwise the first month of the dates.
 */
fun defaultExpectedMonth(range: DateRange, today: LocalDate = LocalDate.now()): YearMonth {
    val first = YearMonth.from(range.start)
    return when {
        first == YearMonth.from(range.end) -> first
        !today.isBefore(range.start) && !today.isAfter(range.end) -> YearMonth.from(today)
        else -> first
    }
}