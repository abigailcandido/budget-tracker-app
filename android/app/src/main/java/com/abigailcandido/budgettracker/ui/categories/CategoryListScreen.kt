package com.abigailcandido.budgettracker.ui.categories

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.TrackerCategoryResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryListScreen(
    trackerName: String,
    state: CategoryListUiState,
    onBack: () -> Unit,
    onAddTopLevelClick: () -> Unit,
    onAddSubcategoryClick: (TrackerCategoryResponse) -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onNewNameChange: (String) -> Unit,
    onMakeGlobalChange: (Boolean) -> Unit,
    onWantsSubcategoriesChange: (Boolean) -> Unit,
    onSubcategoryDraftChange: (String) -> Unit,
    onAddSubcategoryName: () -> Unit,
    onRemoveSubcategoryName: (String) -> Unit,
    onSubmitAdd: () -> Unit,
    onDismissAdd: () -> Unit,
    onEnabledChange: (Int, Boolean) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissActionError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(trackerName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTopLevelClick,
                text = { Text("Add category") },
                icon = {},
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> CenteredMessage(innerPadding) {
                CircularProgressIndicator()
            }

            state.errorMessage != null -> CenteredMessage(innerPadding) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            state.parents.isEmpty() -> CenteredMessage(innerPadding) {
                Text("No categories yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Add one for this tracker only, or share it with " +
                            "every tracker.",
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
                item {
                    Text(
                        text = "Switched on means $trackerName can file spending " +
                                "under it. Switching one off changes this tracker " +
                                "only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                items(state.parents, key = { it.id }) { parent ->
                    CategoryFamilyCard(
                        parent = parent,
                        children = state.childrenByParent[parent.id].orEmpty(),
                        isExpanded = parent.id in state.expandedParentIds,
                        busyIds = state.busyCategoryIds,
                        onToggleExpanded = { onToggleExpanded(parent.id) },
                        onEnabledChange = onEnabledChange,
                        onAddSubcategory = { onAddSubcategoryClick(parent) },
                        onDelete = onDeleteClick,
                    )
                }
            }
        }
    }

    if (state.addRequest != null) {
        AddCategoryDialog(
            trackerName = trackerName,
            state = state,
            onNewNameChange = onNewNameChange,
            onMakeGlobalChange = onMakeGlobalChange,
            onWantsSubcategoriesChange = onWantsSubcategoriesChange,
            onSubcategoryDraftChange = onSubcategoryDraftChange,
            onAddSubcategoryName = onAddSubcategoryName,
            onRemoveSubcategoryName = onRemoveSubcategoryName,
            onSubmit = onSubmitAdd,
            onDismiss = onDismissAdd,
        )
    }

    val pendingDelete = state.categories.firstOrNull { it.id == state.pendingDeleteId }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete ${pendingDelete.name}?") },
            text = {
                Text(
                    when {
                        pendingDelete.hasChildren ->
                            "This category has subcategories. Delete those first."

                        pendingDelete.isGlobal ->
                            "${pendingDelete.name} is shared, so deleting it " +
                                    "removes it from every tracker — not just " +
                                    "$trackerName. To stop using it here only, " +
                                    "switch it off instead."

                        else ->
                            "This removes the category permanently."
                    }
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

    if (state.actionError != null) {
        AlertDialog(
            onDismissRequest = onDismissActionError,
            title = { Text("That didn't work") },
            text = { Text(state.actionError) },
            confirmButton = {
                TextButton(onClick = onDismissActionError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun AddCategoryDialog(
    trackerName: String,
    state: CategoryListUiState,
    onNewNameChange: (String) -> Unit,
    onMakeGlobalChange: (Boolean) -> Unit,
    onWantsSubcategoriesChange: (Boolean) -> Unit,
    onSubcategoryDraftChange: (String) -> Unit,
    onAddSubcategoryName: () -> Unit,
    onRemoveSubcategoryName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val request = state.addRequest ?: return
    val existing = state.existingGlobalMatch
    val blocking = state.blockingDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (request) {
                    is AddRequest.TopLevel -> "Add category"
                    is AddRequest.Subcategory -> "Add under ${request.parentName}"
                }
            )
        },
        text = {
            Column(
                // The dialog can grow with every subcategory added, so its
                // content scrolls rather than pushing the buttons off screen.
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        text = "${request.parentName} is shared across every " +
                                "tracker, so this subcategory will be too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (existing != null) {
                    Text(
                        text = "\"${existing.name}\" already exists as a shared " +
                                "category. Rather than creating a second one with " +
                                "the same name, this will switch the existing one " +
                                "on for $trackerName. To give it subcategories, " +
                                "use Add subcategory on its card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (request is AddRequest.TopLevel) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Share with every tracker",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (state.makeGlobal) {
                                    "Other trackers will see it, switched off " +
                                            "until they turn it on."
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
                        text = "You already have a category called " +
                                "\"${blocking.name}\" here.",
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
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = state.canSubmit) {
                Text(
                    when {
                        state.isCreating -> "Working…"
                        existing != null -> "Switch it on"
                        else -> "Add"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isCreating) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CategoryFamilyCard(
    parent: TrackerCategoryResponse,
    children: List<TrackerCategoryResponse>,
    isExpanded: Boolean,
    busyIds: Set<Int>,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Int, Boolean) -> Unit,
    onAddSubcategory: () -> Unit,
    onDelete: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(top = 12.dp, bottom = 4.dp),
        ) {
            CategoryRow(
                name = parent.name,
                subtitle = if (parent.isGlobal) "Shared" else "Only this tracker",
                isEnabled = parent.isEnabled,
                isBusy = parent.id in busyIds,
                indent = 16.dp,
                onEnabledChange = { onEnabledChange(parent.id, it) },
            )

            if (children.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (children.size == 1) {
                            "1 subcategory"
                        } else {
                            "${children.size} subcategories"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (isExpanded) "Hide ▲" else "Show ▼",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (isExpanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    children.forEach { child ->
                        CategoryRow(
                            name = child.name,
                            subtitle = null,
                            isEnabled = child.isEnabled,
                            isBusy = child.id in busyIds,
                            indent = 36.dp,
                            onEnabledChange = { onEnabledChange(child.id, it) },
                            trailing = {
                                TextButton(onClick = { onDelete(child.id) }) {
                                    Text("Delete")
                                }
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onAddSubcategory) { Text("Add subcategory") }
                TextButton(onClick = { onDelete(parent.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    subtitle: String?,
    isEnabled: Boolean,
    isBusy: Boolean,
    indent: Dp,
    onEnabledChange: (Boolean) -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onEnabledChange,
            enabled = !isBusy,
        )
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