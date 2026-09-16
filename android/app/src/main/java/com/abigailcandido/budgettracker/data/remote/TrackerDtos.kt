package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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