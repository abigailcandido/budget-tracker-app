package com.abigailcandido.budgettracker.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The "add subcategories" part of a create dialog: a switch that reveals a
 * field for typing names one at a time, and the list collected so far.
 *
 * Nothing is created here. The names are only gathered, then sent with the
 * parent in a single request so the family appears together or not at all.
 */
@Composable
fun SubcategoryDraftEditor(
    wantsSubcategories: Boolean,
    draft: String,
    names: List<String>,
    enabled: Boolean,
    onWantsSubcategoriesChange: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Add subcategories", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "For example Bills › Electricity, Water.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = wantsSubcategories,
                onCheckedChange = onWantsSubcategoriesChange,
                enabled = enabled,
            )
        }

        if (wantsSubcategories) {
            val trimmed = draft.trim()
            val alreadyAdded = names.any { it.equals(trimmed, ignoreCase = true) }
            val canAdd = enabled && trimmed.isNotEmpty() && !alreadyAdded

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = { Text("Subcategory name") },
                    singleLine = true,
                    enabled = enabled,
                    isError = alreadyAdded,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAdd, enabled = canAdd) { Text("Add") }
            }

            if (alreadyAdded) {
                Text(
                    text = "Already in the list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            names.forEach { name ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "• $name",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    TextButton(onClick = { onRemove(name) }, enabled = enabled) {
                        Text("Remove")
                    }
                }
            }

            if (names.isEmpty() && trimmed.isEmpty()) {
                Text(
                    text = "None yet. Type a name and tap Add.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The list with one more name added, unless it's blank or already there. */
internal fun List<String>.withSubcategory(draft: String): List<String> {
    val name = draft.trim()
    if (name.isEmpty() || any { it.equals(name, ignoreCase = true) }) return this
    return this + name
}

/**
 * The names to send. Anything still sitting in the field counts too: typing a
 * name and tapping the main Add button without tapping the small one first is
 * an easy slip, and silently dropping that name would be worse than keeping it.
 */
internal fun subcategoriesToSend(
    wantsSubcategories: Boolean,
    names: List<String>,
    draft: String,
): List<String> =
    if (wantsSubcategories) names.withSubcategory(draft) else emptyList()