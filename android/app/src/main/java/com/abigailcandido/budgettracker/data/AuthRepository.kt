package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.AuthApi
import com.abigailcandido.budgettracker.data.remote.TokenResponse
import com.abigailcandido.budgettracker.data.remote.UserResponse

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
        tokenStore.withToken { token -> api.me(token) }

    suspend fun setPin(password: String, pin: String): ApiResult<Unit> =
        tokenStore.withToken { token -> api.setPin(token, password, pin) }

    suspend fun verifyPin(pin: String): ApiResult<Unit> =
        tokenStore.withToken { token -> api.verifyPin(token, pin) }

    /**
     * The local token is cleared whether or not the server call succeeds. A user
     * who taps "log out" on a train with no signal must still end up logged out
     * on this device; the server-side session is replaced on next login anyway.
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
}