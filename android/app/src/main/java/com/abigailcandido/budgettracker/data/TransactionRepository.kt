package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.EmailCodeResponse
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.PromoteOtherRequest
import com.abigailcandido.budgettracker.data.remote.PromoteOtherResponse
import com.abigailcandido.budgettracker.data.remote.TransactionApi
import com.abigailcandido.budgettracker.data.remote.TransactionCreateRequest
import com.abigailcandido.budgettracker.data.remote.TransactionDeleteRequest
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.TransactionRestoreRequest
import com.abigailcandido.budgettracker.data.remote.TrackerSummaryResponse
import com.abigailcandido.budgettracker.data.remote.TransactionUpdateRequest
import java.time.LocalDate

class TransactionRepository(
    private val tokenStore: TokenStore,
    private val api: TransactionApi = TransactionApi(),
) {

    /** Entries dated from start to end, both days included. */
    suspend fun listForTracker(
        trackerId: Int,
        start: LocalDate,
        end: LocalDate,
    ): ApiResult<List<TransactionResponse>> =
        tokenStore.withToken { token ->
            api.listForTracker(token, trackerId, start.toString(), end.toString())
        }

    /** Each wallet's opening, income, expenses and ending balance from start to end. */
    suspend fun summary(
        trackerId: Int,
        start: LocalDate,
        end: LocalDate,
    ): ApiResult<TrackerSummaryResponse> =
        tokenStore.withToken { token ->
            api.summary(token, trackerId, start.toString(), end.toString())
        }

    suspend fun create(
        walletId: Int,
        type: EntryType,
        amount: String,
        categoryId: Int,
        date: String,
        description: String?,
        otherName: String? = null,
    ): ApiResult<TransactionResponse> =
        tokenStore.withToken { token ->
            api.create(
                token,
                TransactionCreateRequest(
                    walletId = walletId,
                    type = type.apiValue,
                    amount = amount.trim(),
                    categoryId = categoryId,
                    transactionDate = date,
                    description = description.cleaned(),
                    otherName = otherName.cleaned(),
                ),
            )
        }

    /**
     * Saves a correction. For an opening balance, pass null for the wallet,
     * type and category: those cannot change, and leaving them out is what
     * tells the server so.
     */
    suspend fun update(
        transactionId: Int,
        walletId: Int?,
        type: EntryType?,
        categoryId: Int?,
        amount: String,
        date: String,
        description: String?,
        otherName: String? = null,
        reason: String? = null,
    ): ApiResult<TransactionResponse> =
        tokenStore.withToken { token ->
            api.update(
                token,
                transactionId,
                TransactionUpdateRequest(
                    walletId = walletId,
                    type = type?.apiValue,
                    categoryId = categoryId,
                    amount = amount.trim(),
                    transactionDate = date,
                    description = description.cleaned(),
                    otherName = otherName.cleaned(),
                    reason = reason.cleaned(),
                ),
            )
        }

    /** Emails the code that deleting this entry needs, to the account's address. */
    suspend fun sendDeleteCode(transactionId: Int): ApiResult<EmailCodeResponse> =
        tokenStore.withToken { token -> api.deleteCode(token, transactionId) }

    /** The entry stays in the list, marked deleted. The server checks the code. */
    suspend fun delete(transactionId: Int, reason: String, code: String): ApiResult<Unit> =
        tokenStore.withToken { token ->
            api.delete(token, transactionId, TransactionDeleteRequest(reason.trim(), code))
        }

    /** Brings a deleted entry back as a new version. The deletion stays in its history. */
    suspend fun restore(transactionId: Int, reason: String?): ApiResult<TransactionResponse> =
        tokenStore.withToken { token ->
            api.restore(token, transactionId, TransactionRestoreRequest(reason.cleaned()))
        }

    /**
     * Gives an Others entry a category of its own, named after it unless a
     * different name is given, and moves every Others entry of that name into
     * it. Under the top-level Others, the new category can hold a sub-category.
     */
    suspend fun promoteOther(
        transactionId: Int,
        name: String?,
        subcategoryName: String?,
    ): ApiResult<PromoteOtherResponse> =
        tokenStore.withToken { token ->
            api.promoteOther(
                token,
                transactionId,
                PromoteOtherRequest(name.cleaned(), subcategoryName.cleaned()),
            )
        }

    /** Every version of an entry and every reversal, oldest first. */
    suspend fun history(transactionId: Int): ApiResult<List<TransactionResponse>> =
        tokenStore.withToken { token -> api.history(token, transactionId) }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}