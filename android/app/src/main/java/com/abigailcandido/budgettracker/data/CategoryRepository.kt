package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.CategoryApi
import com.abigailcandido.budgettracker.data.remote.CategoryResponse
import com.abigailcandido.budgettracker.data.remote.TrackerCategoryResponse

class CategoryRepository(
    private val tokenStore: TokenStore,
    private val api: CategoryApi = CategoryApi(),
) {

    suspend fun listAll(): ApiResult<List<CategoryResponse>> =
        tokenStore.withToken { token -> api.list(token) }

    suspend fun listForTracker(
        trackerId: Int,
        onlyEnabled: Boolean = false,
    ): ApiResult<List<TrackerCategoryResponse>> =
        tokenStore.withToken { token -> api.listForTracker(token, trackerId, onlyEnabled) }

    /** A category offered to every tracker, switched on in the ones given. */
    suspend fun createGlobal(
        name: String,
        trackerIds: List<Int>?,
    ): ApiResult<CategoryResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                name = name.trim(),
                parentId = null,
                mainTrackerId = null,
                trackerIds = trackerIds,
            )
        }

    /** A category belonging to one tracker and offered nowhere else. */
    suspend fun createPrivate(
        name: String,
        trackerId: Int,
    ): ApiResult<CategoryResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                name = name.trim(),
                parentId = null,
                mainTrackerId = trackerId,
                trackerIds = null,
            )
        }

    /** A subcategory. Its scope and switches are inherited from its parent. */
    suspend fun createSubcategory(
        name: String,
        parentId: Int,
    ): ApiResult<CategoryResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                name = name.trim(),
                parentId = parentId,
                mainTrackerId = null,
                trackerIds = null,
            )
        }

    suspend fun rename(categoryId: Int, name: String): ApiResult<CategoryResponse> =
        tokenStore.withToken { token -> api.rename(token, categoryId, name.trim()) }

    suspend fun delete(categoryId: Int): ApiResult<Unit> =
        tokenStore.withToken { token -> api.delete(token, categoryId) }

    // The screen thinks in terms of "this switch is on or off", so the
    // repository is where that collapses into the right verb. Leaving this
    // decision to the ViewModel would leak an HTTP detail into UI logic.
    suspend fun setEnabled(
        trackerId: Int,
        categoryId: Int,
        enabled: Boolean,
    ): ApiResult<Unit> =
        tokenStore.withToken { token ->
            if (enabled) {
                api.enableForTracker(token, trackerId, categoryId)
            } else {
                api.disableForTracker(token, trackerId, categoryId)
            }
        }
}