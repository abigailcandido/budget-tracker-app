package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

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
        
    suspend fun requestPinReset(token: String): ApiResult<MessageResponse> =
        callReturningBody("Could not send the reset link") {
            client.post("auth/pin/reset-request") { bearer(token) }
        }

    suspend fun requestPasswordReset(email: String): ApiResult<MessageResponse> =
        callReturningBody("Could not send the reset link") {
            client.post("auth/password/reset-request") {
                setBody(PasswordResetEmailRequest(email = email))
            }
        }
}