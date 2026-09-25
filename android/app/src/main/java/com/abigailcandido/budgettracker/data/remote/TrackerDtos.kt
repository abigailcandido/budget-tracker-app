package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackerCreateRequest(
    val name: String,
    // Null is not "no categories" — it's "didn't say", and the server then
    // switches on all of them. An empty list is the real "no categories".
    // Because null is the declared default, the field is left out of the JSON
    // entirely rather than sent as an explicit null.
    @SerialName("category_ids") val categoryIds: List<Int>? = null,
)

@Serializable
data class TrackerNameRequest(
    val name: String,
)

@Serializable
data class TrackerResponse(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String,
)