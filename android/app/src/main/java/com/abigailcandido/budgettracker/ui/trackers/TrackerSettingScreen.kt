package com.abigailcandido.budgettracker.ui.trackers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import com.abigailcandido.budgettracker.data.remote.entryType

/** The longest tracker or wallet name the server keeps. */
const val NAME_MAX_LENGTH = 255

/** The dialog that renames the tracker, or one of its wallets, while it is open. */
data class RenameForm(
    /** The wallet being renamed, or null for the tracker itself. */
    val walletId: Int?,
    val name: String,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = !isSaving && name.isNotBlank()
}

/**
 * The tracker's settings: its name, its categories, its wallets, and
 * deleting it. It shares the tracker screen's ViewModel, so a renamed or
 * deleted wallet shows on the tracker screen straight away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerSettingsScreen(
    trackerName: String,
    state: TrackerUiState,
    onBack: () -> Unit,
    onRenameTrackerClick: () -> Unit,
    onOpenCategories: () -> Unit,
    onRenameWalletClick: (Int) -> Unit,
    onDeleteWalletClick: (Int) -> Unit,
    onRenameNameChange: (String) -> Unit,
    onSaveRename: () -> Unit,
    onDismissRename: () -> Unit,
    onConfirmDeleteWallet: () -> Unit,
    onDismissDeleteWallet: () -> Unit,
    onDeleteTrackerClick: () -> Unit,
    onConfirmDeleteTracker: () -> Unit,
    onDismissDeleteTracker: () -> Unit,
    onDismissActionError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsHeading("Tracker")
            SettingsRow(
                title = trackerName,
                subtitle = "The tracker's name",
                actions = {
                    TextButton(onClick = onRenameTrackerClick) { Text("Rename") }
                },
            )
            HorizontalDivider()
            SettingsRow(
                title = "Categories",
                subtitle = "Switch categories on or off, and add your own",
                actions = {
                    TextButton(onClick = onOpenCategories) { Text("Open") }
                },
            )

            SettingsHeading("Wallets")
            if (state.wallets.isEmpty()) {
                SettingsNote("No wallets yet. Add one from the tracker screen.")
            }
            state.wallets.forEach { wallet ->
                SettingsRow(
                    title = walletTitle(wallet, state.showsCurrency),
                    subtitle = "Balance ${wallet.currency} ${formatMoney(wallet.balance)}",
                    actions = {
                        TextButton(onClick = { onRenameWalletClick(wallet.id) }) { Text("Rename") }
                        TextButton(onClick = { onDeleteWalletClick(wallet.id) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
                HorizontalDivider()
            }
            SettingsNote(
                "Entries look their wallet's name up, so a new name shows on every " +
                        "entry and transfer in the wallet straight away."
            )

            SettingsHeading("Delete tracker")
            SettingsNote(
                if (state.wallets.isEmpty()) {
                    "This tracker has no wallets left, so it can be deleted."
                } else {
                    "A tracker can be deleted once its wallets are, so nothing with " +
                            "money in it disappears by accident."
                }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDeleteTrackerClick,
                enabled = state.wallets.isEmpty() && !state.isDeletingTracker,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete this tracker")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val renameForm = state.renameForm
    if (renameForm != null) {
        val renamedWallet = state.wallets.firstOrNull { it.id == renameForm.walletId }
        AlertDialog(
            onDismissRequest = onDismissRename,
            title = {
                Text(if (renamedWallet != null) "Rename ${renamedWallet.name}" else "Rename tracker")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameForm.name,
                        onValueChange = onRenameNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !renameForm.isSaving,
                        isError = renameForm.error != null,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (renameForm.error != null) {
                        Text(
                            text = renameForm.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onSaveRename, enabled = renameForm.canSave) {
                    Text(if (renameForm.isSaving) "Saving…" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRename, enabled = !renameForm.isSaving) {
                    Text("Cancel")
                }
            },
        )
    }

    val pendingDelete = state.wallets.firstOrNull { it.id == state.pendingDeleteWalletId }
    if (pendingDelete != null) {
        // Only entries in the range on the tracker screen are loaded, so this
        // catches the common case early. Entries from other months are still
        // caught by the server, whose refusal appears in the "That didn't
        // work" dialog. Deleted entries no longer count.
        val entryCount = state.transactions.count {
            (it.walletId == pendingDelete.id || it.toWalletId == pendingDelete.id) &&
                    it.entryType != EntryType.Opening &&
                    !it.isDeleted
        }

        AlertDialog(
            onDismissRequest = onDismissDeleteWallet,
            title = {
                Text(
                    if (entryCount > 0) {
                        "${pendingDelete.name} has entries"
                    } else {
                        "Delete ${pendingDelete.name}?"
                    }
                )
            },
            text = {
                Text(
                    when {
                        entryCount > 0 ->
                            "It has $entryCount " +
                                    (if (entryCount == 1) "entry" else "entries") +
                                    " in ${state.range.label}. Delete those first, so " +
                                    "no history disappears by accident."

                        pendingDelete.balance == "0.00" ->
                            "This removes the wallet permanently."

                        else ->
                            "This wallet still holds ${pendingDelete.currency} " +
                                    "${formatMoney(pendingDelete.balance)}. Deleting it " +
                                    "discards that balance — the money is not moved " +
                                    "anywhere."
                    }
                )
            },
            confirmButton = {
                if (entryCount > 0) {
                    TextButton(onClick = onDismissDeleteWallet) { Text("OK") }
                } else {
                    TextButton(onClick = onConfirmDeleteWallet) { Text("Delete") }
                }
            },
            dismissButton = {
                if (entryCount == 0) {
                    TextButton(onClick = onDismissDeleteWallet) { Text("Cancel") }
                }
            },
        )
    }

    if (state.isDeleteTrackerOpen) {
        AlertDialog(
            onDismissRequest = onDismissDeleteTracker,
            title = { Text("Delete $trackerName?") },
            text = { Text("This removes the tracker permanently, with its category switches and expected amounts.") },
            confirmButton = {
                TextButton(onClick = onConfirmDeleteTracker, enabled = !state.isDeletingTracker) {
                    Text(if (state.isDeletingTracker) "Deleting…" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteTracker, enabled = !state.isDeletingTracker) {
                    Text("Cancel")
                }
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

/** "Cash", or "Cash (PHP)" once the tracker mixes currencies. */
private fun walletTitle(wallet: WalletResponse, showsCurrency: Boolean): String =
    if (showsCurrency) "${wallet.name} (${wallet.currency})" else wallet.name

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** A title, a line under it, and its buttons on the right. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}