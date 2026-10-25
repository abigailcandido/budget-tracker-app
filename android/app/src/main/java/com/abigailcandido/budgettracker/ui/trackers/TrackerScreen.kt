package com.abigailcandido.budgettracker.ui.trackers

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import com.abigailcandido.budgettracker.data.remote.entryType

private val WalletCardHeight = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    trackerName: String,
    state: TrackerUiState,
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onWalletClick: (Int) -> Unit,
    onShowAllWallets: () -> Unit,
    onFilterSelected: (EntryFilter) -> Unit,
    onAddWalletClick: () -> Unit,
    onWalletNameChange: (String) -> Unit,
    onWalletCurrencyChange: (String) -> Unit,
    onOpeningBalanceChange: (String) -> Unit,
    onConfirmCreateWallet: () -> Unit,
    onDismissCreateWallet: () -> Unit,
    onDeleteWalletClick: (Int) -> Unit,
    onConfirmDeleteWallet: () -> Unit,
    onDismissDeleteWallet: () -> Unit,
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
                actions = {
                    TextButton(onClick = onOpenCategories) { Text("Categories") }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> CenteredMessage(Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }

            state.errorMessage != null -> CenteredMessage(Modifier.padding(innerPadding)) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                WalletStrip(
                    wallets = state.wallets,
                    selectedWalletId = state.selectedWalletId,
                    onWalletClick = onWalletClick,
                    onAddWalletClick = onAddWalletClick,
                )

                val selected = state.selectedWallet
                if (selected != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Showing ${selected.name} only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDeleteWalletClick(selected.id) }) {
                            Text("Delete wallet")
                        }
                        TextButton(onClick = onShowAllWallets) { Text("Show all") }
                    }
                }

                EntryFilterTabs(selected = state.filter, onSelect = onFilterSelected)

                EntryList(state = state, modifier = Modifier.weight(1f))
            }
        }
    }

    if (state.isCreateWalletOpen) {
        AlertDialog(
            onDismissRequest = onDismissCreateWallet,
            title = { Text("New wallet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.newWalletName,
                        onValueChange = onWalletNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !state.isCreatingWallet,
                        isError = state.createWalletError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.newWalletCurrency,
                        onValueChange = onWalletCurrencyChange,
                        label = { Text("Currency") },
                        singleLine = true,
                        enabled = !state.isCreatingWallet,
                        supportingText = { Text("Three letters, such as PHP or USD") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.newOpeningBalance,
                        onValueChange = onOpeningBalanceChange,
                        label = { Text("Opening balance") },
                        singleLine = true,
                        enabled = !state.isCreatingWallet,
                        placeholder = { Text("0.00") },
                        supportingText = { Text("What's in it right now. Leave blank for zero.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.createWalletError != null) {
                        Text(
                            text = state.createWalletError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmCreateWallet, enabled = state.canCreateWallet) {
                    Text(if (state.isCreatingWallet) "Adding…" else "Add")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateWallet, enabled = !state.isCreatingWallet) {
                    Text("Cancel")
                }
            },
        )
    }

    val pendingDelete = state.wallets.firstOrNull { it.id == state.pendingDeleteWalletId }

    if (pendingDelete != null) {
        // Checked here first, so the user is told why before tapping Delete
        // rather than after. The server refuses it anyway.
        val entryCount = state.transactions.count {
            it.walletId == pendingDelete.id && it.entryType != EntryType.Opening
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
                                    ". Delete those first, so no history disappears " +
                                    "by accident."

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
private fun WalletStrip(
    wallets: List<WalletResponse>,
    selectedWalletId: Int?,
    onWalletClick: (Int) -> Unit,
    onAddWalletClick: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(wallets, key = { it.id }) { wallet ->
            WalletCard(
                wallet = wallet,
                isSelected = wallet.id == selectedWalletId,
                onClick = { onWalletClick(wallet.id) },
            )
        }

        item(key = "add-wallet") {
            OutlinedCard(
                onClick = onAddWalletClick,
                modifier = Modifier
                    .width(120.dp)
                    .height(WalletCardHeight),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+ New wallet",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletCard(
    wallet: WalletResponse,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .width(160.dp)
            .height(WalletCardHeight),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = wallet.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = wallet.currency,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatMoney(wallet.balance),
                style = MaterialTheme.typography.titleMedium,
                // Below zero is allowed, but it should never go unnoticed.
                color = if (isNegative(wallet.balance)) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryFilterTabs(
    selected: EntryFilter,
    onSelect: (EntryFilter) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        EntryFilter.entries.forEach { filter ->
            Tab(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                text = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun EntryList(
    state: TrackerUiState,
    modifier: Modifier = Modifier,
) {
    if (state.wallets.isEmpty()) {
        CenteredMessage(modifier) {
            Text("No wallets yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a wallet above — your cash, a bank account, an " +
                        "e-wallet. Its opening balance becomes its first entry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val entries = state.visibleTransactions

    if (entries.isEmpty()) {
        CenteredMessage(modifier) {
            Text(
                text = when (state.filter) {
                    EntryFilter.All -> "No entries yet"
                    EntryFilter.Expenses -> "No expenses yet"
                    EntryFilter.Income -> "No income yet"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        return
    }

    // The server already sends entries newest first, and grouping keeps that
    // order, so each day's heading appears above that day's entries.
    val byDate = entries.groupBy { it.transactionDate }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
    ) {
        byDate.forEach { (date, dayEntries) ->
            item(key = "date-$date") {
                Text(
                    text = displayDate(date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }

            items(dayEntries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    showWallet = state.selectedWalletId == null,
                    showCurrency = state.showsCurrency,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: TransactionResponse,
    showWallet: Boolean,
    showCurrency: Boolean,
) {
    val title = entryTitle(entry)
    val details = listOfNotNull(
        entry.walletName.takeIf { showWallet },
        entry.description?.takeIf { it != title },
    ).joinToString(" · ")

    val amount = signedAmount(entry)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (showCurrency) "${entry.currency} $amount" else amount,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                entry.entryType == EntryType.Income -> MaterialTheme.colorScheme.primary
                entry.entryType == EntryType.Opening && isNegative(entry.amount) ->
                    MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            },
        )
    }
}

@Composable
private fun CenteredMessage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}