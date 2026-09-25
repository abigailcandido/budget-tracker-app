package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WalletCreateRequest(
    val name: String,
    val currency: String,
    @SerialName("opening_balance") val openingBalance: String,
)

@Serializable
data class WalletNameRequest(
    val name: String,
)

@Serializable
data class WalletResponse(
    val id: Int,
    @SerialName("main_tracker_id") val mainTrackerId: Int? = null,
    val name: String,
    val currency: String,
    val balance: String,
    @SerialName("created_at") val createdAt: String,
)