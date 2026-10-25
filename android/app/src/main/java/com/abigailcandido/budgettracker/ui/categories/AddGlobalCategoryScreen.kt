package com.abigailcandido.budgettracker.ui.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGlobalCategoryScreen(
    state: GlobalCategoryListUiState,
    onNewNameChange: (String) -> Unit,
    onTrackerToggle: (Int) -> Unit,
    onWantsSubcategoriesChange: (Boolean) -> Unit,
    onSubcategoryDraftChange: (String) -> Unit,
    onAddSubcategoryName: () -> Unit,
    onRemoveSubcategoryName: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    val request = state.addRequest
    val blocking = state.blockingDuplicate

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (request is AddRequest.Subcategory) {
                            "Add under ${request.parentName}"
                        } else {
                            "Add global ${state.selectedKind.singular} category"
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancel, enabled = !state.isCreating) {
                        Text("Cancel")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.isCreating -> "Working…"
                            request is AddRequest.Subcategory -> "Add subcategory"
                            else -> "Create category"
                        }
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.newName,
                onValueChange = onNewNameChange,
                label = { Text("Name") },
                singleLine = true,
                enabled = !state.isCreating,
                isError = blocking != null || state.createError != null,
                modifier = Modifier.fillMaxWidth(),
            )

            if (request is AddRequest.Subcategory) {
                Text(
                    text = "It will be switched on wherever ${request.parentName} " +
                            "already is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Switch it on in",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "It stays available to every tracker either way — " +
                                "this just decides which ones start with it on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    when {
                        state.isLoadingTrackers -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Loading your trackers…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        state.trackers.isEmpty() -> Text(
                            text = "You have no trackers yet. The category will be " +
                                    "available to any you create later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> state.trackers.forEach { tracker ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.isCreating) {
                                        onTrackerToggle(tracker.id)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = state.selectedTrackerIds
                                        ?.contains(tracker.id) == true,
                                    onCheckedChange = { onTrackerToggle(tracker.id) },
                                    enabled = !state.isCreating,
                                )
                                Text(
                                    text = tracker.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                SubcategoryDraftEditor(
                    wantsSubcategories = state.wantsSubcategories,
                    draft = state.subcategoryDraft,
                    names = state.subcategoryNames,
                    enabled = !state.isCreating,
                    onWantsSubcategoriesChange = onWantsSubcategoriesChange,
                    onDraftChange = onSubcategoryDraftChange,
                    onAdd = onAddSubcategoryName,
                    onRemove = onRemoveSubcategoryName,
                )
            }

            if (blocking != null) {
                Text(
                    text = "There's already a category called \"${blocking.name}\" here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.createError != null) {
                Text(
                    text = state.createError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}