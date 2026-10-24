package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.AuthApi
import com.abigailcandido.budgettracker.data.remote.MessageResponse
import com.abigailcandido.budgettracker.data.remote.TokenResponse
import com.abigailcandido.budgettracker.data.remote.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AuthRepository(
    private val tokenStore: TokenStore,
    private val api: AuthApi = AuthApi(),
) {

    /**
     * Whether a session token is stored, emitted again every time that changes.
     * Any request answered with a 401 clears the token, so this is how the app
     * learns that its session ended somewhere else — after a password reset, say.
     */
    val hasSession: Flow<Boolean> = tokenStore.token
        .map { token -> token != null }
        .distinctUntilChanged()

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

    /** Emails a PIN reset link to the signed-in account. Returns what to tell the user. */
    suspend fun requestPinReset(): ApiResult<String> =
        tokenStore.withToken { token -> api.requestPinReset(token) }.detailOnly()

    /**
     * Asks for a password reset link. The server answers the same way whether or
     * not the address has an account, so success here does not mean one exists.
     */
    suspend fun requestPasswordReset(email: String): ApiResult<String> =
        api.requestPasswordReset(email.trim().lowercase()).detailOnly()

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

    private fun ApiResult<MessageResponse>.detailOnly(): ApiResult<String> = when (this) {
        is ApiResult.Success -> ApiResult.Success(data.detail)
        is ApiResult.Failure -> this
    }
}