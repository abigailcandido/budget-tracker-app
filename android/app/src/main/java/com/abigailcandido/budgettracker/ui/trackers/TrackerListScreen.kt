package com.abigailcandido.budgettracker.ui.trackers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerListScreen(
    state: TrackerListUiState,
    bottomBar: @Composable () -> Unit,
    onOpenTracker: (Int) -> Unit,
    onAddClick: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Your trackers") },
                actions = {
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                text = { Text("New tracker") },
                icon = {},
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> CenteredMessage(padding = innerPadding) {
                CircularProgressIndicator()
            }

            state.errorMessage != null -> CenteredMessage(padding = innerPadding) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            state.trackers.isEmpty() -> CenteredMessage(padding = innerPadding) {
                Text(
                    text = "No trackers yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A tracker is one set of books — your own money, " +
                            "or someone else's you look after.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 88.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Grouped by kind from the start. Savings trackers get a
                // section of their own below this one when they arrive, so
                // the list won't need reorganising then.
                item {
                    Text(
                        text = "Main trackers",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                items(state.trackers, key = { it.id }) { tracker ->
                    // Deleting a tracker is done from its Settings, inside it.
                    TrackerRow(
                        name = tracker.name,
                        onOpen = { onOpenTracker(tracker.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackerRow(
    name: String,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CenteredMessage(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}