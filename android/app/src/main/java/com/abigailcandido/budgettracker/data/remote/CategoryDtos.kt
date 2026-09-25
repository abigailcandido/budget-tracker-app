package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CategoryCreateRequest(
    val name: String,
    // Each of these three is omitted from the JSON when null, because null is
    // the declared default. That matters: the server reads an absent field as
    // "the client didn't say" and an explicit empty list as "the user chose
    // none", and those must not collapse into each other.
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("main_tracker_id") val mainTrackerId: Int? = null,
    @SerialName("tracker_ids") val trackerIds: List<Int>? = null,
)

@Serializable
data class CategoryNameRequest(
    val name: String,
)

@Serializable
data class CategoryResponse(
    val id: Int,
    val name: String,
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
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("is_global") val isGlobal: Boolean,
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("has_children") val hasChildren: Boolean = false,
)