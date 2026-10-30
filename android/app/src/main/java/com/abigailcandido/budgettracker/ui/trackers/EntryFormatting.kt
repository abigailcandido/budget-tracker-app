package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONEY_FORMAT = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
private const val MINUS = "−"

/** "-1234.5" becomes "−1,234.50". Amounts stay strings end to end, so nothing is rounded. */
fun formatMoney(amount: String): String {
    val value = amount.toBigDecimalOrNull() ?: return amount
    val digits = MONEY_FORMAT.format(value.abs())
    return if (value.signum() < 0) "$MINUS$digits" else digits
}

fun isNegative(amount: String): Boolean =
    (amount.toBigDecimalOrNull()?.signum() ?: 0) < 0

/**
 * An expense shows with a minus, an income with a plus, an opening balance as
 * it is. A transfer has no sign of its own, except seen from one of its
 * wallets: a minus where it left, and what arrived, with a plus, where it went.
 */
fun signedAmount(entry: TransactionResponse, forWalletId: Int? = null): String =
    when (entry.entryType) {
        EntryType.Expense -> MINUS + formatMoney(entry.amount)
        EntryType.Income -> "+" + formatMoney(entry.amount)
        EntryType.Opening -> formatMoney(entry.amount)
        EntryType.Transfer -> when (forWalletId) {
            null -> formatMoney(entry.amount)
            entry.toWalletId -> "+" + formatMoney(entry.toAmount ?: entry.amount)
            else -> MINUS + formatMoney(entry.amount)
        }
    }

/**
 * "Bills › Electricity", "Groceries", or "Opening balance". An entry under
 * Others shows what it was too: "Others: Pet food", "Bills › Others: Internet".
 */
fun entryTitle(entry: TransactionResponse): String {
    val category = when {
        entry.entryType == EntryType.Opening -> return "Opening balance"
        entry.entryType == EntryType.Transfer ->
            return "Transfer: ${entry.walletName} → ${entry.toWalletName ?: "a deleted wallet"}"
        entry.parentCategoryName != null && entry.categoryName != null ->
            "${entry.parentCategoryName} › ${entry.categoryName}"
        else -> entry.categoryName ?: "No category"
    }
    return if (entry.otherName != null) "$category: ${entry.otherName}" else category
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

/** "2026-10-26" becomes "Mon, 26 Oct 2026", even for today. */
fun longDate(isoDate: String): String =
    runCatching { LocalDate.parse(isoDate).format(DAY_FORMAT) }.getOrDefault(isoDate)

/** When a row was recorded, in the phone's own time zone: "26 Oct 2026, 3:31 PM". */
fun displayDateTime(isoDateTime: String): String =
    runCatching {
        OffsetDateTime.parse(isoDateTime)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DATE_TIME_FORMAT)
    }.getOrDefault(isoDateTime)

/** "Expense", "Income" or "Opening balance". */
fun entryTypeLabel(entry: TransactionResponse): String = when (entry.entryType) {
    EntryType.Expense -> "Expense"
    EntryType.Income -> "Income"
    EntryType.Opening -> "Opening balance"
    EntryType.Transfer -> "Transfer"
}

/** A transfer between wallets of two currencies, which the Forex tab lists. */
val TransactionResponse.isForex: Boolean
    get() = entryType == EntryType.Transfer && toCurrency != null && toCurrency != currency

/**
 * A forex transfer's rate, as one unit of the dearer currency: sending
 * PHP 560.00 for USD 10.00 is "1 USD = 56.00 PHP". Null when an amount is
 * missing or zero.
 */
fun forexRate(entry: TransactionResponse): String? {
    val sent = entry.amount.toBigDecimalOrNull()?.abs() ?: return null
    val received = entry.toAmount?.toBigDecimalOrNull()?.abs() ?: return null
    val toCurrency = entry.toCurrency ?: return null
    if (sent.signum() == 0 || received.signum() == 0) return null

    val (unit, rate, per) = if (sent >= received) {
        Triple(toCurrency, sent.divide(received, 4, RoundingMode.HALF_UP), entry.currency)
    } else {
        Triple(entry.currency, received.divide(sent, 4, RoundingMode.HALF_UP), toCurrency)
    }
    // Up to four decimals, and never fewer than two: 56.00, 56.1234.
    val trimmed = rate.stripTrailingZeros()
    val shown = if (trimmed.scale() < 2) trimmed.setScale(2) else trimmed
    return "1 $unit = ${shown.toPlainString()} $per"
}