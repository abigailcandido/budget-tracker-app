package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a category is for money going out or money coming in. The server
 * keeps the two apart, so Salary never appears where groceries are chosen.
 */
enum class CategoryKind(val apiValue: String) {
    Expense("expense"),
    Income("income");

    companion object {
        /** Anything unrecognised reads as an expense, which every older category is. */
        fun fromApi(value: String?): CategoryKind =
            entries.firstOrNull { it.apiValue == value } ?: Expense
    }
}

@Serializable
data class CategoryCreateRequest(
    val name: String,
    // Each of these is left out of the JSON when null, because null is the
    // declared default. The server reads "absent" and "empty" differently for
    // tracker_ids, so that distinction has to survive the trip.
    val kind: String? = null,
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("main_tracker_id") val mainTrackerId: Int? = null,
    @SerialName("tracker_ids") val trackerIds: List<Int>? = null,
    @SerialName("subcategory_names") val subcategoryNames: List<String>? = null,
)

@Serializable
data class CategoryNameRequest(
    val name: String,
)

@Serializable
data class CategoryResponse(
    val id: Int,
    val name: String,
    val kind: String = "expense",
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("main_tracker_id") val mainTrackerId: Int? = null,
    @SerialName("is_global") val isGlobal: Boolean,
    @SerialName("has_children") val hasChildren: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class TrackerCategoryResponse(
    val id: Int,
    val name: String,
    val kind: String = "expense",
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("is_global") val isGlobal: Boolean,
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("has_children") val hasChildren: Boolean = false,
)

val CategoryResponse.categoryKind: CategoryKind
    get() = CategoryKind.fromApi(kind)

val TrackerCategoryResponse.categoryKind: CategoryKind
    get() = CategoryKind.fromApi(kind)