package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an entry does to its wallet. */
enum class EntryType(val apiValue: String) {
    Expense("expense"),
    Income("income"),
    Opening("opening"),
    // Money moved between two wallets. Only the row where it left is listed;
    // where it arrived comes with it, as toWallet and toAmount.
    Transfer("transfer_out");

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
    // Only under an Others category: what the entry was, such as "Pet food".
    @SerialName("other_name") val otherName: String? = null,
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
    // Always sent too, so moving an entry out of Others drops its name.
    @SerialName("other_name") val otherName: String?,
    // Why the entry is being corrected. Optional, and left out when empty.
    val reason: String? = null,
)

/** Deleting needs a reason and the code just emailed for it. */
@Serializable
data class TransactionDeleteRequest(
    val reason: String,
    val code: String,
)

/** Where the deletion code went, such as "ab•••@gmail.com", and how long it works. */
@Serializable
data class EmailCodeResponse(
    @SerialName("sent_to") val sentTo: String,
    @SerialName("expires_in_minutes") val expiresInMinutes: Int,
)

@Serializable
data class TransactionRestoreRequest(
    // Optional, and left out when empty.
    val reason: String? = null,
)

@Serializable
data class TransferCreateRequest(
    @SerialName("from_wallet_id") val fromWalletId: Int,
    @SerialName("to_wallet_id") val toWalletId: Int,
    val amount: String,
    // Only between two currencies: what arrives, in the other wallet's currency.
    @SerialName("to_amount") val toAmount: String? = null,
    @SerialName("transaction_date") val transactionDate: String,
    val description: String? = null,
    // Optional: a charge for the transfer, recorded as an expense of its own.
    val fee: String? = null,
    @SerialName("fee_category_id") val feeCategoryId: Int? = null,
)

@Serializable
data class TransferUpdateRequest(
    @SerialName("from_wallet_id") val fromWalletId: Int,
    @SerialName("to_wallet_id") val toWalletId: Int,
    val amount: String,
    // Left out between wallets of one currency, where what arrives is what leaves.
    @SerialName("to_amount") val toAmount: String? = null,
    @SerialName("transaction_date") val transactionDate: String,
    // No default, so it is always sent, null included, and a cleared note is cleared.
    val description: String?,
    val reason: String? = null,
)

@Serializable
data class PromoteOtherRequest(
    // Left out to use the entry's own Others name.
    val name: String? = null,
    // Only under the top-level Others: a sub-category to make under the new category.
    @SerialName("subcategory_name") val subcategoryName: String? = null,
    // Left out unless true: a new category then belongs to this tracker only.
    @SerialName("is_global") val isGlobal: Boolean = false,
    // The same for the new sub-category. Only a global category can have a
    // global one, so the server ignores it under a category kept to a tracker.
    @SerialName("subcategory_is_global") val subcategoryIsGlobal: Boolean = false,
)

@Serializable
data class PromoteOtherResponse(
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String,
    // False when a category with that name already existed and was used.
    val created: Boolean,
    // Whether it is offered to every tracker, or belongs to this one only.
    @SerialName("is_global") val isGlobal: Boolean = false,
    // How many Others entries with that name moved into it.
    val moved: Int,
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
    // Whether that parent is offered to every tracker. Null without a parent.
    @SerialName("parent_category_is_global") val parentCategoryIsGlobal: Boolean? = null,
    val description: String? = null,
    @SerialName("transaction_date") val transactionDate: String,
    @SerialName("created_at") val createdAt: String,
    // Where this row sits in the entry's history. A correction names the
    // version it replaced, a reversal names the version it cancels, and
    // isReversed marks a version that has since been corrected or deleted.
    @SerialName("replaces_id") val replacesId: Int? = null,
    @SerialName("reverses_id") val reversesId: Int? = null,
    @SerialName("is_reversed") val isReversed: Boolean = false,
    // Deleted entries stay in the lists, marked as deleted and counting for nothing.
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    // The copy that brought a deleted entry back. Like a correction, it names
    // the version it replaced.
    @SerialName("is_restoration") val isRestoration: Boolean = false,
    @SerialName("other_name") val otherName: String? = null,
    // On a reversal: why the entry was corrected or deleted. On a restored
    // entry: why it was restored.
    val reason: String? = null,
    // On a transfer: the wallet the money went to, and what arrived there in
    // that wallet's currency.
    @SerialName("to_wallet_id") val toWalletId: Int? = null,
    @SerialName("to_wallet_name") val toWalletName: String? = null,
    @SerialName("to_currency") val toCurrency: String? = null,
    @SerialName("to_amount") val toAmount: String? = null,
)

val TransactionResponse.entryType: EntryType
    get() = EntryType.fromApi(type)

/** A corrected version of an earlier entry. The lists tag it "Adjusted". */
val TransactionResponse.isAdjusted: Boolean
    get() = replacesId != null

@Serializable
data class WalletSummary(
    @SerialName("wallet_id") val walletId: Int,
    @SerialName("wallet_name") val walletName: String,
    val currency: String,
    val opening: String,
    val income: String,
    val expenses: String,
    @SerialName("transfers_in") val transfersIn: String = "0.00",
    @SerialName("transfers_out") val transfersOut: String = "0.00",
    val ending: String,
)

@Serializable
data class CurrencyTotal(
    val currency: String,
    val opening: String,
    val income: String,
    val expenses: String,
    @SerialName("transfers_in") val transfersIn: String = "0.00",
    @SerialName("transfers_out") val transfersOut: String = "0.00",
    val ending: String,
)

@Serializable
data class TrackerSummaryResponse(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val wallets: List<WalletSummary>,
    val totals: List<CurrencyTotal>,
)