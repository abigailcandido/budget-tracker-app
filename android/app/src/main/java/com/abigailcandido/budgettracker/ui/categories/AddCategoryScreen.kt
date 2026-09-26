package com.abigailcandido.budgettracker.ui.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryScreen(
    trackerName: String,
    state: CategoryListUiState,
    onNewNameChange: (String) -> Unit,
    onMakeGlobalChange: (Boolean) -> Unit,
    onWantsSubcategoriesChange: (Boolean) -> Unit,
    onSubcategoryDraftChange: (String) -> Unit,
    onAddSubcategoryName: () -> Unit,
    onRemoveSubcategoryName: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back gesture and Cancel take the same path, so neither can walk away
    // from a category that is part-way through being created.
    BackHandler(onBack = onCancel)

    val request = state.addRequest
    val existing = state.existingGlobalMatch
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
                            "Add category"
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
                            existing != null -> "Switch it on"
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

            if (request is AddRequest.Subcategory && request.parentIsGlobal) {
                Text(
                    text = "${request.parentName} is shared across every tracker, " +
                            "so this subcategory will be too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (existing != null) {
                Text(
                    text = "\"${existing.name}\" already exists as a shared category. " +
                            "Rather than creating a second one with the same name, " +
                            "this will switch the existing one on for $trackerName. " +
                            "To give it subcategories, use Add subcategory on its card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (request is AddRequest.TopLevel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Share with every tracker",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (state.makeGlobal) {
                                "Other trackers will see it, switched off until " +
                                        "they turn it on."
                            } else {
                                "Only $trackerName will see it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.makeGlobal,
                        onCheckedChange = onMakeGlobalChange,
                        enabled = !state.isCreating,
                    )
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
                    text = "You already have a category called \"${blocking.name}\" here.",
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