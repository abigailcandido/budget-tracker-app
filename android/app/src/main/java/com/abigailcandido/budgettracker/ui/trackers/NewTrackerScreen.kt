package com.abigailcandido.budgettracker.ui.trackers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTrackerScreen(
    state: TrackerListUiState,
    onNameChange: (String) -> Unit,
    onCategoryToggle: (Int) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The system back gesture takes the same route as Cancel, so it obeys the
    // same rule: a tracker that is mid-creation can't be walked away from.
    BackHandler(onBack = onCancel)

    val parents = state.availableCategories.filter { it.parentId == null }
    val childrenByParent = state.availableCategories
        .filter { it.parentId != null }
        .groupBy { it.parentId!! }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("New tracker") },
                navigationIcon = {
                    TextButton(onClick = onCancel, enabled = !state.isCreating) {
                        Text("Cancel")
                    }
                },
            )
        },
        bottomBar = {
            // Pinned below the list so it stays reachable however many
            // categories there are, and lifts above the keyboard.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Button(
                    onClick = onCreate,
                    enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isCreating) "Creating…" else "Create tracker")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.newName,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !state.isCreating,
                    isError = state.createError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.createError != null) {
                item {
                    Text(
                        text = state.createError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                    Text(
                        text = "Categories to start with",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "You can change these later from inside the tracker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                state.isLoadingCategories -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                }

                parents.isEmpty() -> item {
                    Text(
                        text = "You don't have any categories yet. Create the " +
                                "tracker now, then add categories from the " +
                                "Categories tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> items(parents, key = { it.id }) { parent ->
                    Column {
                        CategoryCheckRow(
                            name = parent.name,
                            checked = state.selectedCategoryIds?.contains(parent.id) == true,
                            indent = 0.dp,
                            enabled = !state.isCreating,
                            onToggle = { onCategoryToggle(parent.id) },
                        )
                        childrenByParent[parent.id].orEmpty().forEach { child ->
                            CategoryCheckRow(
                                name = child.name,
                                checked = state.selectedCategoryIds?.contains(child.id) == true,
                                indent = 32.dp,
                                enabled = !state.isCreating,
                                onToggle = { onCategoryToggle(child.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCheckRow(
    name: String,
    checked: Boolean,
    indent: Dp,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
    }
}