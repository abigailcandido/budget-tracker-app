package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an entry does to its wallet. */
enum class EntryType(val apiValue: String) {
    Expense("expense"),
    Income("income"),
    Opening("opening");

    companion object {
        fun fromApi(value: String): EntryType =
            entries.firstOrNull { it.apiValue == value } ?: Expense
    }
}

@Serializable
data class TransactionCreateRequest(
    @SerialName("wallet_id") val walletId: Int,
    val type: String,
    // Amounts travel as strings, exactly as typed, so nothing is rounded on
    // the way to the server.
    val amount: String,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("transaction_date") val transactionDate: String,
    val description: String? = null,
)

@Serializable
data class TransactionUpdateRequest(
    // Left out of the JSON when null, so an opening balance can be corrected
    // without sending a type or category it is not allowed to have.
    @SerialName("wallet_id") val walletId: Int? = null,
    val type: String? = null,
    @SerialName("category_id") val categoryId: Int? = null,
    val amount: String,
    @SerialName("transaction_date") val transactionDate: String,
    // No default, so it is always sent — null included. That is how a
    // description the user cleared actually gets cleared on the server.
    val description: String?,
)

@Serializable
data class TransactionResponse(
    val id: Int,
    @SerialName("wallet_id") val walletId: Int,
    @SerialName("wallet_name") val walletName: String,
    val currency: String,
    val type: String,
    val amount: String,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("parent_category_name") val parentCategoryName: String? = null,
    val description: String? = null,
    @SerialName("transaction_date") val transactionDate: String,
    @SerialName("created_at") val createdAt: String,
)

val TransactionResponse.entryType: EntryType
    get() = EntryType.fromApi(type)

@Serializable
data class WalletSummary(
    @SerialName("wallet_id") val walletId: Int,
    @SerialName("wallet_name") val walletName: String,
    val currency: String,
    val opening: String,
    val income: String,
    val expenses: String,
    val ending: String,
)

@Serializable
data class CurrencyTotal(
    val currency: String,
    val opening: String,
    val income: String,
    val expenses: String,
    val ending: String,
)

@Serializable
data class TrackerSummaryResponse(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val wallets: List<WalletSummary>,
    val totals: List<CurrencyTotal>,
)