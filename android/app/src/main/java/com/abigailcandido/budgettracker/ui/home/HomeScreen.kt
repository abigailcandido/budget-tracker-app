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
import com.abigailcandido.budgettracker.ui.trackers.TrackerListScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListViewModel
import com.abigailcandido.budgettracker.ui.categories.CategoryHubScreen

/**
 * The two top-level tabs.
 *
 * These are deliberately NOT navigation destinations. Bottom-bar tabs are
 * meant to be siblings you flick between, not a trail you back out of — if
 * they were destinations, the system back button would walk backwards through
 * every tab you'd visited, which is not what anyone expects of a bottom bar.
 *
 * The tracker list's ViewModel survives the switch: `viewModel()` stores it
 * against this screen's back stack entry, so leaving the tab and coming back
 * finds the same instance with its list already loaded.
 */
@Composable
fun HomeScreen(
    trackerRepository: TrackerRepository,
    categoryRepository: CategoryRepository,
    onOpenTracker: (Int, String) -> Unit,
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
                onAddClick = viewModel::onAddClick,
                onNewNameChange = viewModel::onNewNameChange,
                onCategoryToggle = viewModel::onCategoryToggle,
                onConfirmCreate = viewModel::createTracker,
                onDismissCreate = viewModel::onDismissCreate,
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