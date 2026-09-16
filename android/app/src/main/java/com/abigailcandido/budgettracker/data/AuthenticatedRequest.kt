package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import io.ktor.http.HttpStatusCode

internal const val NOT_SIGNED_IN = "You are not signed in."

/**
 * Runs an authenticated call with the stored token, and discards that token if
 * the server rejects it.
 *
 * Written as an extension on TokenStore so any repository holding one gets the
 * behaviour for free. The alternative — each repository remembering to check for
 * a 401 — is the kind of rule that holds until the day someone forgets it, and
 * then the app sits there retrying forever with credentials the server threw
 * away.
 */
internal suspend fun <T> TokenStore.withToken(
    call: suspend (String) -> ApiResult<T>,
): ApiResult<T> {
    val token = currentToken() ?: return ApiResult.Failure(NOT_SIGNED_IN)

    val result = call(token)

    if (result is ApiResult.Failure && result.status == HttpStatusCode.Unauthorized) {
        clear()
    }

    return result
}