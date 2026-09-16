package com.abigailcandido.budgettracker.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object TrackerList

@Serializable
data class TrackerDetail(
    val trackerId: Int,
    val trackerName: String,
)