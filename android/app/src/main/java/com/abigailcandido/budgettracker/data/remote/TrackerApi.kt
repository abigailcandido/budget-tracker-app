package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class TrackerApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun list(token: String): ApiResult<List<TrackerResponse>> =
        callReturningBody("Could not load your trackers") {
            client.get("trackers") { bearer(token) }
        }

    suspend fun create(
        token: String,
        name: String,
        categoryIds: List<Int>?,
    ): ApiResult<TrackerResponse> =
        callReturningBody("Could not create the tracker") {
            client.post("trackers") {
                bearer(token)
                setBody(TrackerCreateRequest(name = name, categoryIds = categoryIds))
            }
        }

    suspend fun rename(token: String, trackerId: Int, name: String): ApiResult<TrackerResponse> =
        callReturningBody("Could not rename the tracker") {
            client.patch("trackers/$trackerId") {
                bearer(token)
                setBody(TrackerNameRequest(name))
            }
        }

    suspend fun delete(token: String, trackerId: Int): ApiResult<Unit> =
        callReturningNothing("Could not delete the tracker") {
            client.delete("trackers/$trackerId") { bearer(token) }
        }
}