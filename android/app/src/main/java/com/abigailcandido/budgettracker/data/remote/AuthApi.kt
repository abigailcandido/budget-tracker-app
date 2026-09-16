package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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

private const val NETWORK_MESSAGE =
    "Could not reach the server. Check your connection and try again."

class AuthApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun register(email: String, password: String): ApiResult<TokenResponse> =
        callReturningBody("Could not create the account") {
            client.post("auth/register") {
                setBody(RegisterRequest(email = email, password = password))
            }
        }

    suspend fun login(email: String, password: String): ApiResult<TokenResponse> =
        callReturningBody("Could not sign in") {
            client.post("auth/login") {
                setBody(LoginRequest(email = email, password = password))
            }
        }

    suspend fun me(token: String): ApiResult<UserResponse> =
        callReturningBody("Could not load your account") {
            client.get("auth/me") { bearer(token) }
        }

    suspend fun logout(token: String): ApiResult<Unit> =
        callReturningNothing("Could not sign out") {
            client.post("auth/logout") { bearer(token) }
        }

    suspend fun setPin(token: String, password: String, pin: String): ApiResult<Unit> =
        callReturningNothing("Could not save your PIN") {
            client.post("auth/pin") {
                bearer(token)
                setBody(SetPinRequest(password = password, pin = pin))
            }
        }

    suspend fun verifyPin(token: String, pin: String): ApiResult<Unit> =
        callReturningNothing("Incorrect PIN") {
            client.post("auth/pin/verify") {
                bearer(token)
                setBody(VerifyPinRequest(pin = pin))
            }
        }
}

private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend inline fun <reified T> callReturningBody(
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

private suspend fun callReturningNothing(
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

private suspend fun HttpResponse.errorMessage(fallback: String): String =
    try {
        ApiClient.json
            .decodeFromString(ApiErrorBody.serializer(), bodyAsText())
            .readableMessage(fallback)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (other: Exception) {
        fallback
    }