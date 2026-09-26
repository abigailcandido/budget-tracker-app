package com.abigailcandido.budgettracker.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.ui.categories.CategoryHubScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListViewModel

/**
 * The two top-level tabs.
 *
 * These are deliberately NOT navigation destinations. Bottom-bar tabs are
 * siblings you flick between, not a trail you back out of.
 *
 * The tracker list's ViewModel lives on this screen's back stack entry, which
 * is also how the New tracker page reaches the very same instance.
 */
@Composable
fun HomeScreen(
    trackerRepository: TrackerRepository,
    categoryRepository: CategoryRepository,
    onOpenTracker: (Int, String) -> Unit,
    onOpenNewTracker: () -> Unit,
    onOpenGlobalCategories: () -> Unit,
    onOpenTrackerCategories: () -> Unit,
    onSignOut: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Trackers) }

    val bottomBar: @Composable () -> Unit = {
        HomeBottomBar(selected = selectedTab, onSelect = { selectedTab = it })
    }

    when (selectedTab) {
        HomeTab.Trackers -> {
            val viewModel: TrackerListViewModel = viewModel(
                factory = TrackerListViewModel.factory(
                    trackerRepository,
                    categoryRepository,
                ),
            )
            val state by viewModel.uiState.collectAsState()

            TrackerListScreen(
                state = state,
                bottomBar = bottomBar,
                onOpenTracker = { trackerId ->
                    val name = state.trackers
                        .firstOrNull { it.id == trackerId }
                        ?.name
                        .orEmpty()
                    onOpenTracker(trackerId, name)
                },
                onDeleteClick = viewModel::onDeleteClick,
                onConfirmDelete = viewModel::confirmDelete,
                onDismissDelete = viewModel::onDismissDelete,
                onAddClick = {
                    // Prepare the form first — this starts loading the category
                    // list — then open the page that shows it.
                    viewModel.onAddClick()
                    onOpenNewTracker()
                },
                onRetry = viewModel::refresh,
                onSignOut = onSignOut,
            )
        }

        HomeTab.Categories -> CategoryHubScreen(
            bottomBar = bottomBar,
            onOpenGlobal = onOpenGlobalCategories,
            onOpenTrackerCategories = onOpenTrackerCategories,
            onSignOut = onSignOut,
        )
    }
}