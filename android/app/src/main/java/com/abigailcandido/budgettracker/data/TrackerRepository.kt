package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.TrackerApi
import com.abigailcandido.budgettracker.data.remote.TrackerResponse

class TrackerRepository(
    private val tokenStore: TokenStore,
    private val api: TrackerApi = TrackerApi(),
) {

    suspend fun list(): ApiResult<List<TrackerResponse>> =
        tokenStore.withToken { token -> api.list(token) }

    suspend fun create(name: String): ApiResult<TrackerResponse> =
        tokenStore.withToken { token -> api.create(token, name.trim()) }

    suspend fun rename(trackerId: Int, name: String): ApiResult<TrackerResponse> =
        tokenStore.withToken { token -> api.rename(token, trackerId, name.trim()) }

    suspend fun delete(trackerId: Int): ApiResult<Unit> =
        tokenStore.withToken { token -> api.delete(token, trackerId) }
}