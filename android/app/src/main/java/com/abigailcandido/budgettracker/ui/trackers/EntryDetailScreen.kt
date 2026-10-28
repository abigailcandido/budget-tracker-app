package com.abigailcandido.budgettracker.ui.trackers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import com.abigailcandido.budgettracker.data.remote.isAdjusted

/**
 * One entry, read-only, with its history once it has been corrected, deleted
 * or restored. Edit asks for the PIN, and only then opens the form. Delete
 * asks for a reason and a code sent to the account's email. A deleted entry
 * can only be looked at, or restored. An entry under Others can be added to
 * the categories from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    detail: EntryDetailState,
    showsCurrency: Boolean,
    onBack: () -> Unit,
    onEditClick: () -> Unit,
    onPinChange: (String) -> Unit,
    onConfirmPin: () -> Unit,
    onDismissPin: () -> Unit,
    onRetryHistory: () -> Unit,
    onDeleteClick: () -> Unit,
    onDeleteReasonChange: (String) -> Unit,
    onSendDeleteCode: () -> Unit,
    onDeleteCodeChange: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onRestoreClick: () -> Unit,
    onRestoreReasonChange: (String) -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    onPromoteClick: () -> Unit,
    onPromoteNameChange: (String) -> Unit,
    onPromoteWithSubcategoryChange: (Boolean) -> Unit,
    onPromoteSubcategoryNameChange: (String) -> Unit,
    onConfirmPromote: () -> Unit,
    onDismissPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val entry = detail.entry
    val isOpening = entry.entryType == EntryType.Opening
    val deletion = detail.deletion

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            entry.isDeleted && isOpening -> "Deleted opening balance"
                            entry.isDeleted -> "Deleted transaction"
                            isOpening -> "Opening balance"
                            else -> "Transaction"
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (entry.isDeleted) {
                    Button(onClick = onRestoreClick, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Restoring makes it count towards its wallet again. " +
                                "The deletion stays in its history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (detail.canBePromoted) {
                        OutlinedButton(onClick = onPromoteClick, modifier = Modifier.fillMaxWidth()) {
                            Text("Add “${entry.otherName}” to categories")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Delete")
                        }
                        Button(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                            Text("Edit")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Edit asks for your PIN. Delete sends a code to your email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = amountText(entry, showsCurrency),
                style = MaterialTheme.typography.headlineMedium.withDeletedStyle(entry),
                color = amountColor(entry),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entryTypeLabel(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EntryTag(entry = entry, modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(Modifier.height(16.dp))

            if (!isOpening) DetailRow("Category", entryTitle(entry))
            DetailRow("Wallet", entry.walletName)
            DetailRow("Date", longDate(entry.transactionDate))
            DetailRow("Note", entry.description ?: "—")
            // A correction or a restore is a new row, so its time is when that happened.
            DetailRow(
                when {
                    entry.isRestoration -> "Restored"
                    entry.isAdjusted -> "Corrected"
                    else -> "Recorded"
                },
                displayDateTime(entry.createdAt),
            )
            if (entry.isRestoration && entry.reason != null) {
                DetailRow("Reason", entry.reason)
            }
            // The deletion is a reversal in the history, so these appear once it has loaded.
            if (deletion != null) {
                DetailRow("Deleted", displayDateTime(deletion.createdAt))
                DetailRow("Reason", deletion.reason ?: "—")
            }

            if (detail.hasHistory) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                HistorySection(
                    detail = detail,
                    showsCurrency = showsCurrency,
                    onRetry = onRetryHistory,
                )
            }
        }
    }

    when {
        detail.isPinOpen -> PinDialog(
            detail = detail,
            onPinChange = onPinChange,
            onConfirm = onConfirmPin,
            onDismiss = onDismissPin,
        )

        detail.isDeleteOpen -> DeleteDialog(
            detail = detail,
            onReasonChange = onDeleteReasonChange,
            onSendCode = onSendDeleteCode,
            onCodeChange = onDeleteCodeChange,
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
        )

        detail.isRestoreOpen -> RestoreDialog(
            detail = detail,
            onReasonChange = onRestoreReasonChange,
            onConfirm = onConfirmRestore,
            onDismiss = onDismissRestore,
        )

        detail.isPromoteOpen -> PromoteDialog(
            detail = detail,
            onNameChange = onPromoteNameChange,
            onWithSubcategoryChange = onPromoteWithSubcategoryChange,
            onSubcategoryNameChange = onPromoteSubcategoryNameChange,
            onConfirm = onConfirmPromote,
            onDismiss = onDismissPromote,
        )
    }
}

/**
 * Deleting asks why, then sends a six-digit code to the account's email,
 * which the server checks. The entry is not erased: it stays in the list
 * marked Deleted, with the reason kept in its history.
 */
@Composable
private fun DeleteDialog(
    detail: EntryDetailState,
    onReasonChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = detail.entry
    val isBusy = detail.isSendingCode || detail.isDeleting
    val sentTo = detail.codeSentTo

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (entry.entryType == EntryType.Opening) {
                    "Delete this opening balance?"
                } else {
                    "Delete this transaction?"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "It stays in the list marked Deleted, with its history, and " +
                            "stops counting towards ${entry.walletName}'s balance. You " +
                            "can restore it later.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = detail.deleteReason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason") },
                    placeholder = { Text("Such as Entered twice") },
                    enabled = !isBusy,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sentTo == null) {
                    Text(
                        text = "To confirm, a 6-digit code will be sent to your email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "A 6-digit code was sent to $sentTo. It works for " +
                                "${detail.codeMinutes} minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = detail.deleteCode,
                        onValueChange = onCodeChange,
                        label = { Text("Code from your email") },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onSendCode, enabled = detail.canSendCode) {
                        Text(if (detail.isSendingCode) "Sending…" else "Send a new code")
                    }
                }
                if (detail.deleteError != null) {
                    Text(
                        text = detail.deleteError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (sentTo == null) {
                TextButton(onClick = onSendCode, enabled = detail.canSendCode) {
                    Text(if (detail.isSendingCode) "Sending…" else "Send code")
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = detail.canConfirmDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(if (detail.isDeleting) "Deleting…" else "Delete")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Cancel") }
        },
    )
}

/** Brings a deleted entry back. The deletion and the restore both stay in its history. */
@Composable
private fun RestoreDialog(
    detail: EntryDetailState,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = detail.entry

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (entry.entryType == EntryType.Opening) {
                    "Restore this opening balance?"
                } else {
                    "Restore this transaction?"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "It counts towards ${entry.walletName}'s balance again. " +
                            "The deletion stays in its history.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = detail.restoreReason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    placeholder = { Text("Such as Deleted by mistake") },
                    enabled = !detail.isRestoring,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (detail.restoreError != null) {
                    Text(
                        text = detail.restoreError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !detail.isRestoring) {
                Text(if (detail.isRestoring) "Restoring…" else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !detail.isRestoring) { Text("Cancel") }
        },
    )
}

/**
 * Gives an Others entry a category of its own. The name starts as what the
 * entry was called and can be changed. Under the top-level Others, the new
 * category can hold a sub-category too. If a category with that name already
 * exists at the same level, in any mix of capitals, the entries go there and
 * no second one is made.
 */
@Composable
private fun PromoteDialog(
    detail: EntryDetailState,
    onNameChange: (String) -> Unit,
    onWithSubcategoryChange: (Boolean) -> Unit,
    onSubcategoryNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = detail.entry
    // "Bills" for an entry under Bills › Others, or nothing at the top level.
    val parent = entry.parentCategoryName
    val nameIsOthers = isOthersName(detail.promoteName)
    val showsSubcategory = detail.canPromoteWithSubcategory && detail.promoteWithSubcategory
    val subcategoryIsOthers = showsSubcategory && isOthersName(detail.promoteSubcategoryName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = (if (parent != null) {
                        "This becomes a sub-category of $parent. "
                    } else {
                        "This becomes a category of its own. "
                    }) + "Every entry under Others called “${entry.otherName}” moves " +
                            "into it, and each move is kept in its history. If the " +
                            "category already exists, they move there instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = detail.promoteName,
                    onValueChange = onNameChange,
                    label = { Text("Category name") },
                    singleLine = true,
                    enabled = !detail.isPromoting,
                    isError = nameIsOthers,
                    supportingText = if (nameIsOthers) {
                        { Text("Choose a name other than Others.") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (detail.canPromoteWithSubcategory) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Give it a sub-category",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = detail.promoteWithSubcategory,
                            onCheckedChange = onWithSubcategoryChange,
                            enabled = !detail.isPromoting,
                        )
                    }
                }
                if (showsSubcategory) {
                    OutlinedTextField(
                        value = detail.promoteSubcategoryName,
                        onValueChange = onSubcategoryNameChange,
                        label = { Text("Sub-category") },
                        placeholder = { Text("Such as Food") },
                        singleLine = true,
                        enabled = !detail.isPromoting,
                        isError = subcategoryIsOthers,
                        supportingText = if (subcategoryIsOthers) {
                            { Text("Choose a name other than Others.") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (detail.promoteError != null) {
                    Text(
                        text = detail.promoteError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = detail.canConfirmPromote) {
                Text(if (detail.isPromoting) "Adding…" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !detail.isPromoting) { Text("Cancel") }
        },
    )
}

/** Every version of the entry, oldest first, and when and why each was replaced or deleted. */
@Composable
private fun HistorySection(
    detail: EntryDetailState,
    showsCurrency: Boolean,
    onRetry: () -> Unit,
) {
    Text(text = "History", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Changes never overwrite a transaction. Every earlier version is " +
                "kept, with when and why it was replaced, deleted or restored.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    when {
        detail.isLoadingHistory -> CircularProgressIndicator(
            modifier = Modifier
                .padding(8.dp)
                .size(20.dp),
            strokeWidth = 2.dp,
        )

        detail.historyError != null -> Column {
            Text(
                text = detail.historyError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("Try again") }
        }

        else -> versionsOf(detail.history).forEachIndexed { index, version ->
            if (index > 0) HorizontalDivider()
            VersionItem(version = version, showsCurrency = showsCurrency)
        }
    }
}

@Composable
private fun VersionItem(
    version: EntryVersion,
    showsCurrency: Boolean,
) {
    val entry = version.entry
    val details = listOfNotNull(
        entryTitle(entry).takeIf { entry.entryType != EntryType.Opening },
        entry.walletName,
        longDate(entry.transactionDate),
        entry.description,
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = when (version.end) {
                VersionEnd.Current -> "${version.name} · current"
                VersionEnd.Deleted -> "${version.name} · deleted"
                VersionEnd.Replaced -> version.name
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (version.end == VersionEnd.Deleted) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "${entryTypeLabel(entry)} · ${amountText(entry, showsCurrency)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A restored version was made when it was restored, with that reason.
        if (entry.isRestoration && entry.reason != null) {
            Text(
                text = "Reason for restoring: ${entry.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val made = if (entry.isRestoration) "Restored" else "Recorded"
        val ended = version.endedAt?.let { endedAt ->
            val word = if (version.end == VersionEnd.Deleted) "Deleted" else "Replaced"
            " · $word ${displayDateTime(endedAt)}"
        }
        Text(
            text = "$made ${displayDateTime(entry.createdAt)}${ended.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (version.reason != null) {
            Text(
                text = "Reason: ${version.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinDialog(
    detail: EntryDetailState,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter your PIN") },
        text = {
            Column {
                Text(
                    text = "Your PIN is needed before a transaction can be changed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = detail.pin,
                    onValueChange = onPinChange,
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !detail.isCheckingPin,
                    isError = detail.pinError != null,
                    supportingText = if (detail.pinError != null) {
                        { Text(detail.pinError) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = detail.canCheckPin) {
                Text(if (detail.isCheckingPin) "Checking…" else "Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !detail.isCheckingPin) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The small label beside an entry that is not as first recorded: "Deleted",
 * "Restored" or "Adjusted". Nothing for an entry that never changed.
 */
@Composable
internal fun EntryTag(entry: TransactionResponse, modifier: Modifier = Modifier) {
    when {
        entry.isDeleted -> DeletedTag(modifier)
        entry.isRestoration -> AdjustedTag(modifier, text = "Restored")
        entry.isAdjusted -> AdjustedTag(modifier)
    }
}

/** The small "Adjusted" label on an entry that has been corrected, or "Restored". */
@Composable
internal fun AdjustedTag(modifier: Modifier = Modifier, text: String = "Adjusted") {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The small "Deleted" label on an entry that has been deleted but kept for the record. */
@Composable
internal fun DeletedTag(modifier: Modifier = Modifier) {
    Text(
        text = "Deleted",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A deleted entry's amount is struck through, since it no longer counts. */
internal fun TextStyle.withDeletedStyle(entry: TransactionResponse): TextStyle =
    if (entry.isDeleted) copy(textDecoration = TextDecoration.LineThrough) else this

/** The amount as the lists show it, with the currency when the tracker mixes them. */
internal fun amountText(entry: TransactionResponse, showsCurrency: Boolean): String {
    val amount = signedAmount(entry)
    return if (showsCurrency) "${entry.currency} $amount" else amount
}

/** Income in the main colour and money owed in red; a deleted entry is greyed out. */
@Composable
internal fun amountColor(entry: TransactionResponse): Color = when {
    entry.isDeleted -> MaterialTheme.colorScheme.onSurfaceVariant
    entry.entryType == EntryType.Income -> MaterialTheme.colorScheme.primary
    entry.entryType == EntryType.Opening && isNegative(entry.amount) ->
        MaterialTheme.colorScheme.error
    else -> Color.Unspecified
}