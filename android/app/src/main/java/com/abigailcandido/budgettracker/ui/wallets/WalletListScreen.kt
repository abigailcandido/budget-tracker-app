package com.abigailcandido.budgettracker.ui.wallets

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletListScreen(
    trackerName: String,
    state: WalletListUiState,
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onOpeningBalanceChange: (String) -> Unit,
    onConfirmCreate: () -> Unit,
    onDismissCreate: () -> Unit,
    onDeleteWallet: (Int) -> Unit,
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
                onClick = onAddClick,
                text = { Text("New wallet") },
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

            state.wallets.isEmpty() -> CenteredMessage(innerPadding) {
                Text("No wallets yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A wallet is somewhere money sits — cash in your " +
                            "purse, a bank account, an e-wallet.",
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
                items(state.wallets, key = { it.id }) { wallet ->
                    WalletRow(
                        name = wallet.name,
                        currency = wallet.currency,
                        balance = wallet.balance,
                        onDelete = { onDeleteWallet(wallet.id) },
                    )
                }
            }
        }
    }

    if (state.isCreateDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissCreate,
            title = { Text("New wallet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = onNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !state.isCreating,
                        isError = state.createError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.newCurrency,
                        onValueChange = onCurrencyChange,
                        label = { Text("Currency") },
                        singleLine = true,
                        enabled = !state.isCreating,
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
                        enabled = !state.isCreating,
                        placeholder = { Text("0.00") },
                        supportingText = { Text("What's in it right now. Leave blank for zero.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
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
                    Text(if (state.isCreating) "Adding…" else "Add")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreate, enabled = !state.isCreating) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun WalletRow(
    name: String,
    currency: String,
    balance: String,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$currency $balance",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
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