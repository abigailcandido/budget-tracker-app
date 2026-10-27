package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody

class CategoryApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun list(token: String): ApiResult<List<CategoryResponse>> =
        callReturningBody("Could not load your categories") {
            client.get("categories") { bearer(token) }
        }
        
    suspend fun listForTracker(
        token: String,
        trackerId: Int,
        onlyEnabled: Boolean = false,
    ): ApiResult<List<TrackerCategoryResponse>> =
        callReturningBody("Could not load this tracker's categories") {
            client.get("trackers/$trackerId/categories") {
                bearer(token)
                parameter("only_enabled", onlyEnabled)
            }
        }

    suspend fun create(
        token: String,
        name: String,
        parentId: Int?,
        mainTrackerId: Int?,
        trackerIds: List<Int>?,
        subcategoryNames: List<String>?,
        kind: String? = null,
    ): ApiResult<CategoryResponse> =
        callReturningBody("Could not add the category") {
            client.post("categories") {
                bearer(token)
                setBody(
                    CategoryCreateRequest(
                        name = name,
                        kind = kind,
                        parentId = parentId,
                        mainTrackerId = mainTrackerId,
                        trackerIds = trackerIds,
                        subcategoryNames = subcategoryNames,
                    )
                )
            }
        }

    /** The tracker's Others category, made on the server the first time it is needed. */
    suspend fun othersCategory(
        token: String,
        trackerId: Int,
        request: OthersCategoryRequest,
    ): ApiResult<TrackerCategoryResponse> =
        callReturningBody("Could not file this under Others") {
            client.post("trackers/$trackerId/categories/others") {
                bearer(token)
                setBody(request)
            }
        }

    suspend fun rename(
        token: String,
        categoryId: Int,
        name: String,
    ): ApiResult<CategoryResponse> =
        callReturningBody("Could not rename the category") {
            client.patch("categories/$categoryId") {
                bearer(token)
                setBody(CategoryNameRequest(name))
            }
        }

    suspend fun delete(token: String, categoryId: Int): ApiResult<Unit> =
        callReturningNothing("Could not delete the category") {
            client.delete("categories/$categoryId") { bearer(token) }
        }

    // Switching a category on and off for one tracker is two different HTTP
    // verbs on the same URL rather than one endpoint taking a true/false flag.
    // Both are idempotent: enabling something already enabled does nothing and
    // still succeeds, so a double-tap can't produce an error the user has to
    // interpret.
    suspend fun enableForTracker(
        token: String,
        trackerId: Int,
        categoryId: Int,
    ): ApiResult<Unit> =
        callReturningNothing("Could not switch the category on") {
            client.put("trackers/$trackerId/categories/$categoryId") { bearer(token) }
        }

    suspend fun disableForTracker(
        token: String,
        trackerId: Int,
        categoryId: Int,
    ): ApiResult<Unit> =
        callReturningNothing("Could not switch the category off") {
            client.delete("trackers/$trackerId/categories/$categoryId") { bearer(token) }
        }
}