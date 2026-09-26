package com.abigailcandido.budgettracker.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Home

@Serializable
data object GlobalCategories

@Serializable
data object TrackerPicker

@Serializable
data class TrackerDetail(
    val trackerId: Int,
    val trackerName: String,
)

@Serializable
data class CategoryList(
    val trackerId: Int,
    val trackerName: String,
)

@Serializable
data object NewTracker

@Serializable
data object GlobalCategoryAdd

@Serializable
data class CategoryAdd(
    val trackerId: Int,
    val trackerName: String,
)