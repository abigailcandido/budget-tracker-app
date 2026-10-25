package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONEY_FORMAT = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private const val MINUS = "−"

/** "-1234.5" becomes "−1,234.50". Amounts stay strings end to end, so nothing is rounded. */
fun formatMoney(amount: String): String {
    val value = amount.toBigDecimalOrNull() ?: return amount
    val digits = MONEY_FORMAT.format(value.abs())
    return if (value.signum() < 0) "$MINUS$digits" else digits
}

fun isNegative(amount: String): Boolean =
    (amount.toBigDecimalOrNull()?.signum() ?: 0) < 0

/** An expense shows with a minus, an income with a plus, an opening balance as it is. */
fun signedAmount(entry: TransactionResponse): String = when (entry.entryType) {
    EntryType.Expense -> MINUS + formatMoney(entry.amount)
    EntryType.Income -> "+" + formatMoney(entry.amount)
    EntryType.Opening -> formatMoney(entry.amount)
}

/** "Bills › Electricity", "Groceries", or "Opening balance". */
fun entryTitle(entry: TransactionResponse): String = when {
    entry.entryType == EntryType.Opening -> "Opening balance"
    entry.parentCategoryName != null && entry.categoryName != null ->
        "${entry.parentCategoryName} › ${entry.categoryName}"
    else -> entry.categoryName ?: "No category"
}

/** "2026-10-25" becomes "Today", "Yesterday", or "Fri, 23 Oct 2026". */
fun displayDate(isoDate: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_FORMAT)
    }
}