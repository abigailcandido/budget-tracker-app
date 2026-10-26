package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionApi
import com.abigailcandido.budgettracker.data.remote.TransactionCreateRequest
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
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
                ),
            )
        }

    suspend fun delete(transactionId: Int): ApiResult<Unit> =
        tokenStore.withToken { token -> api.delete(token, transactionId) }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}