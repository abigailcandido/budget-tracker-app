package com.abigailcandido.budgettracker.ui.trackers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import com.abigailcandido.budgettracker.data.remote.isAdjusted

/**
 * One entry, read-only, with its history once it has been corrected. Edit
 * asks for the PIN, and only then opens the form.
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
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val entry = detail.entry
    val isOpening = entry.entryType == EntryType.Opening

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (isOpening) "Opening balance" else "Transaction") },
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
                Button(onClick = onEditClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You'll be asked for your PIN first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                style = MaterialTheme.typography.headlineMedium,
                color = amountColor(entry),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entryTypeLabel(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.isAdjusted) {
                    Spacer(Modifier.width(8.dp))
                    AdjustedTag()
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!isOpening) DetailRow("Category", entryTitle(entry))
            DetailRow("Wallet", entry.walletName)
            DetailRow("Date", longDate(entry.transactionDate))
            DetailRow("Note", entry.description ?: "—")
            // A correction is a new row, so its time is when the change was made.
            DetailRow(if (entry.isAdjusted) "Corrected" else "Recorded", displayDateTime(entry.createdAt))

            if (entry.isAdjusted) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                HistorySection(
                    detail = detail,
                    showsCurrency = showsCurrency,
                    onRetry = onRetryHistory,
                )
            }
        }
    }

    if (detail.isPinOpen) {
        PinDialog(
            detail = detail,
            onPinChange = onPinChange,
            onConfirm = onConfirmPin,
            onDismiss = onDismissPin,
        )
    }
}

/** Every version of the entry, oldest first, and when each was replaced. */
@Composable
private fun HistorySection(
    detail: EntryDetailState,
    showsCurrency: Boolean,
    onRetry: () -> Unit,
) {
    Text(text = "History", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Corrections never overwrite a transaction. Every earlier version " +
                "is kept, with the time it was replaced.",
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
            VersionItem(number = index, version = version, showsCurrency = showsCurrency)
        }
    }
}

@Composable
private fun VersionItem(
    number: Int,
    version: EntryVersion,
    showsCurrency: Boolean,
) {
    val entry = version.entry
    val name = if (number == 0) "Original" else "Correction $number"
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
            text = if (version.replacedAt == null) "$name · current" else name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
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
        Text(
            text = "Recorded ${displayDateTime(entry.createdAt)}" +
                    (version.replacedAt?.let { " · Replaced ${displayDateTime(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** The small "Adjusted" label on an entry that has been corrected. */
@Composable
internal fun AdjustedTag(modifier: Modifier = Modifier) {
    Text(
        text = "Adjusted",
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

/** The amount as the lists show it, with the currency when the tracker mixes them. */
private fun amountText(entry: TransactionResponse, showsCurrency: Boolean): String {
    val amount = signedAmount(entry)
    return if (showsCurrency) "${entry.currency} $amount" else amount
}

@Composable
private fun amountColor(entry: TransactionResponse): Color = when {
    entry.entryType == EntryType.Income -> MaterialTheme.colorScheme.primary
    entry.entryType == EntryType.Opening && isNegative(entry.amount) ->
        MaterialTheme.colorScheme.error
    else -> Color.Unspecified
}