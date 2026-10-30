package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/** The most entries the server returns in one request. */
private const val PAGE_LIMIT = 500

class TransactionApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun listForTracker(
        token: String,
        trackerId: Int,
        startDate: String,
        endDate: String,
    ): ApiResult<List<TransactionResponse>> =
        callReturningBody("Could not load this tracker's entries") {
            client.get("trackers/$trackerId/transactions") {
                bearer(token)
                parameter("start_date", startDate)
                parameter("end_date", endDate)
                parameter("limit", PAGE_LIMIT)
            }
        }

    suspend fun summary(
        token: String,
        trackerId: Int,
        startDate: String,
        endDate: String,
    ): ApiResult<TrackerSummaryResponse> =
        callReturningBody("Could not load this tracker's overview") {
            client.get("trackers/$trackerId/summary") {
                bearer(token)
                parameter("start_date", startDate)
                parameter("end_date", endDate)
            }
        }

    suspend fun create(
        token: String,
        request: TransactionCreateRequest,
    ): ApiResult<TransactionResponse> =
        callReturningBody("Could not save the entry") {
            client.post("transactions") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun update(
        token: String,
        transactionId: Int,
        request: TransactionUpdateRequest,
    ): ApiResult<TransactionResponse> =
        callReturningBody("Could not save your changes") {
            client.patch("transactions/$transactionId") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun createTransfer(
        token: String,
        request: TransferCreateRequest,
    ): ApiResult<TransactionResponse> =
        callReturningBody("Could not save the transfer") {
            client.post("transfers") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun updateTransfer(
        token: String,
        transferId: Int,
        request: TransferUpdateRequest,
    ): ApiResult<TransactionResponse> =
        callReturningBody("Could not save your changes") {
            client.patch("transfers/$transferId") {
                bearer(token)
                setBody(request)
            }
        }

    /** Emails a six-digit code that deleting this entry needs. */
    suspend fun deleteCode(token: String, transactionId: Int): ApiResult<EmailCodeResponse> =
        callReturningBody("Could not send the code") {
            client.post("transactions/$transactionId/delete-code") { bearer(token) }
        }

    suspend fun delete(
        token: String,
        transactionId: Int,
        request: TransactionDeleteRequest,
    ): ApiResult<Unit> =
        callReturningNothing("Could not delete the entry") {
            client.delete("transactions/$transactionId") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun restore(
        token: String,
        transactionId: Int,
        request: TransactionRestoreRequest,
    ): ApiResult<TransactionResponse> =
        callReturningBody("Could not restore the entry") {
            client.post("transactions/$transactionId/restore") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun promoteOther(
        token: String,
        transactionId: Int,
        request: PromoteOtherRequest,
    ): ApiResult<PromoteOtherResponse> =
        callReturningBody("Could not add it to your categories") {
            client.post("transactions/$transactionId/promote-other") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun history(
        token: String,
        transactionId: Int,
    ): ApiResult<List<TransactionResponse>> =
        callReturningBody("Could not load this entry's history") {
            client.get("transactions/$transactionId/history") { bearer(token) }
        }
}