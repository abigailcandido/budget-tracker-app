package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class Failure(
        val message: String,
        val status: HttpStatusCode? = null,
    ) : ApiResult<Nothing>
}

internal const val NETWORK_MESSAGE =
    "Could not reach the server. Check your connection and try again."

internal fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

internal suspend inline fun <reified T> callReturningBody(
    fallback: String,
    block: () -> HttpResponse,
): ApiResult<T> =
    try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(response.body<T>())
        } else {
            ApiResult.Failure(response.errorMessage(fallback), response.status)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        ApiResult.Failure(NETWORK_MESSAGE)
    } catch (other: Exception) {
        ApiResult.Failure(fallback)
    }

internal suspend fun callReturningNothing(
    fallback: String,
    block: suspend () -> HttpResponse,
): ApiResult<Unit> =
    try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(response.errorMessage(fallback), response.status)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        ApiResult.Failure(NETWORK_MESSAGE)
    } catch (other: Exception) {
        ApiResult.Failure(fallback)
    }

internal suspend fun HttpResponse.errorMessage(fallback: String): String =
    try {
        ApiClient.json
            .decodeFromString(ApiErrorBody.serializer(), bodyAsText())
            .readableMessage(fallback)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (other: Exception) {
        fallback
    }