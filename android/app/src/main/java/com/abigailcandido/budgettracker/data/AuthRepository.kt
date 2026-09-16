package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.AuthApi
import com.abigailcandido.budgettracker.data.remote.TokenResponse
import com.abigailcandido.budgettracker.data.remote.UserResponse
import io.ktor.http.HttpStatusCode

private const val NOT_SIGNED_IN = "You are not signed in."

class AuthRepository(
    private val tokenStore: TokenStore,
    private val api: AuthApi = AuthApi(),
) {

    suspend fun isSignedIn(): Boolean = tokenStore.currentToken() != null

    suspend fun register(email: String, password: String): ApiResult<Unit> =
        startSession { api.register(email.trim().lowercase(), password) }

    suspend fun login(email: String, password: String): ApiResult<Unit> =
        startSession { api.login(email.trim().lowercase(), password) }

    suspend fun currentUser(): ApiResult<UserResponse> =
        withToken { token -> api.me(token) }

    suspend fun setPin(password: String, pin: String): ApiResult<Unit> =
        withToken { token -> api.setPin(token, password, pin) }

    suspend fun verifyPin(pin: String): ApiResult<Unit> =
        withToken { token -> api.verifyPin(token, pin) }

    /**
     * The local token is cleared whether or not the server call succeeds. A user
     * who taps "log out" on a train with no signal must still end up logged out
     * on this device; the server-side session expires or is replaced on next login.
     */
    suspend fun logout(): ApiResult<Unit> {
        val token = tokenStore.currentToken()
        val result = if (token == null) ApiResult.Success(Unit) else api.logout(token)
        tokenStore.clear()
        return result
    }

    private suspend fun startSession(
        call: suspend () -> ApiResult<TokenResponse>,
    ): ApiResult<Unit> = when (val result = call()) {
        is ApiResult.Success -> {
            tokenStore.save(result.data.accessToken)
            ApiResult.Success(Unit)
        }
        is ApiResult.Failure -> result
    }

    /**
     * A 401 on an authenticated call means the stored token is no longer valid —
     * most often because the account was signed in on another device, which
     * replaces the session under the one-active-device rule. Clearing it here is
     * what makes the app fall back to the login screen instead of retrying
     * forever with a token the server has already forgotten.
     */
    private suspend fun <T> withToken(
        call: suspend (String) -> ApiResult<T>,
    ): ApiResult<T> {
        val token = tokenStore.currentToken() ?: return ApiResult.Failure(NOT_SIGNED_IN)
        val result = call(token)
        if (result is ApiResult.Failure && result.status == HttpStatusCode.Unauthorized) {
            tokenStore.clear()
        }
        return result
    }
}