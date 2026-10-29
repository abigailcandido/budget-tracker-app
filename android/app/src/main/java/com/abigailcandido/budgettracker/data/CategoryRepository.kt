package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.CategoryApi
import com.abigailcandido.budgettracker.data.remote.CategoryFindOrCreateRequest
import com.abigailcandido.budgettracker.data.remote.CategoryKind
import com.abigailcandido.budgettracker.data.remote.CategoryResponse
import com.abigailcandido.budgettracker.data.remote.OthersCategoryRequest
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
        subcategoryNames: List<String> = emptyList(),
        kind: CategoryKind = CategoryKind.Expense,
    ): ApiResult<CategoryResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                name = name.trim(),
                parentId = null,
                mainTrackerId = null,
                trackerIds = trackerIds,
                subcategoryNames = subcategoryNames.takeIf { it.isNotEmpty() },
                kind = kind.apiValue,
            )
        }

    /** A category belonging to one tracker and offered nowhere else. */
    suspend fun createPrivate(
        name: String,
        trackerId: Int,
        subcategoryNames: List<String> = emptyList(),
        kind: CategoryKind = CategoryKind.Expense,
    ): ApiResult<CategoryResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                name = name.trim(),
                parentId = null,
                mainTrackerId = trackerId,
                trackerIds = null,
                subcategoryNames = subcategoryNames.takeIf { it.isNotEmpty() },
                kind = kind.apiValue,
            )
        }

    /**
     * A subcategory. Its scope, switches and kind are all inherited from its
     * parent, so none of them is sent.
     */
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
                subcategoryNames = null,
            )
        }

    /**
     * The Others category to file an entry under: the top-level one of a kind
     * when parentId is null, or the one under that parent. Made on the server
     * the first time it is needed, and switched on in this tracker.
     */
    suspend fun othersCategory(
        trackerId: Int,
        kind: CategoryKind,
        parentId: Int?,
    ): ApiResult<TrackerCategoryResponse> =
        tokenStore.withToken { token ->
            api.othersCategory(
                token,
                trackerId,
                OthersCategoryRequest(kind = kind.apiValue, parentId = parentId),
            )
        }

    /**
     * A category named on the add form instead of Others, found by name or made:
     * a top-level one (with a sub-category if one is named, which is what comes
     * back), or one of parentId's sub-categories. Switched on in this tracker.
     * Whatever is made belongs to this tracker unless isGlobal is true.
     */
    suspend fun findOrCreate(
        trackerId: Int,
        kind: CategoryKind,
        parentId: Int?,
        name: String,
        subcategoryName: String?,
        isGlobal: Boolean,
    ): ApiResult<TrackerCategoryResponse> =
        tokenStore.withToken { token ->
            api.findOrCreate(
                token,
                trackerId,
                CategoryFindOrCreateRequest(
                    kind = kind.apiValue,
                    parentId = parentId,
                    name = name.trim(),
                    subcategoryName = subcategoryName?.trim()?.takeIf { it.isNotEmpty() },
                    isGlobal = isGlobal,
                ),
            )
        }

    suspend fun rename(categoryId: Int, name: String): ApiResult<CategoryResponse> =
        tokenStore.withToken { token -> api.rename(token, categoryId, name.trim()) }

    suspend fun delete(categoryId: Int): ApiResult<Unit> =
        tokenStore.withToken { token -> api.delete(token, categoryId) }

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