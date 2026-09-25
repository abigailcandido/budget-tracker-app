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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    onDeleteClick: (Int) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onAddClick: () -> Unit,
    onNewNameChange: (String) -> Unit,
    onCategoryToggle: (Int) -> Unit,
    onConfirmCreate: () -> Unit,
    onDismissCreate: () -> Unit,
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
                items(state.trackers, key = { it.id }) { tracker ->
                    TrackerRow(
                        name = tracker.name,
                        onOpen = { onOpenTracker(tracker.id) },
                        onDelete = { onDeleteClick(tracker.id) },
                    )
                }
            }
        }
    }

    if (state.isCreateDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissCreate,
            title = { Text("New tracker") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = onNewNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !state.isCreating,
                        isError = state.createError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = "Categories to start with",
                        style = MaterialTheme.typography.titleSmall,
                    )

                    when {
                        state.isLoadingCategories -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Loading your categories…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        state.availableCategories.isEmpty() -> Text(
                            text = "You don't have any categories yet. Create " +
                                    "the tracker now, then add categories from " +
                                    "the Categories tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // A plain scrolling Column rather than a LazyColumn.
                        // A dialog measures its content with no height limit,
                        // and a LazyColumn asked how tall it wants to be with
                        // no limit crashes rather than guessing.
                        else -> Column(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            state.availableCategories.forEach { category ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !state.isCreating) {
                                            onCategoryToggle(category.id)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = state.selectedCategoryIds
                                            ?.contains(category.id) == true,
                                        onCheckedChange = {
                                            onCategoryToggle(category.id)
                                        },
                                        enabled = !state.isCreating,
                                    )
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "You can change these later from inside the tracker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.createError != null) {
                        Text(
                            text = state.createError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmCreate, enabled = state.canCreate) {
                    Text(if (state.isCreating) "Creating…" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreate, enabled = !state.isCreating) {
                    Text("Cancel")
                }
            },
        )
    }

    val pendingDelete = state.trackers.firstOrNull { it.id == state.pendingDeleteId }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete ${pendingDelete.name}?") },
            text = {
                Text(
                    "This removes the tracker permanently. Its wallets must be " +
                            "deleted first, so nothing with money in it disappears by accident."
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrackerRow(
    name: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDelete) { Text("Delete") }
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