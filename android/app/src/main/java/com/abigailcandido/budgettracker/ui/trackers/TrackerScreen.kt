package com.abigailcandido.budgettracker.ui.trackers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val WalletCardHeight = 96.dp
private val WalletColumnWidth = 120.dp
private val AmountColumnWidth = 100.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    trackerName: String,
    state: TrackerUiState,
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onPreviousRange: () -> Unit,
    onNextRange: () -> Unit,
    onRangeSelected: (DateRange) -> Unit,
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
    onDismissNotice: () -> Unit,
    onRetry: () -> Unit,
    onAddEntryClick: () -> Unit,
    onEntryClick: (TransactionResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustomRange by remember { mutableStateOf(false) }

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
        floatingActionButton = {
            // Only once the screen has loaded and there is a wallet to put an entry in.
            if (!state.isLoading && state.errorMessage == null && state.wallets.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = onAddEntryClick) {
                    Text("+ Add transaction")
                }
            }
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
                RangeBar(
                    range = state.range,
                    onPrevious = onPreviousRange,
                    onNext = onNextRange,
                    onSelect = onRangeSelected,
                    onCustomClick = { showCustomRange = true },
                )

                // Always takes the same space, so nothing jumps when it appears.
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(4.dp))
                }

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

                if (state.filter == EntryFilter.Overview) {
                    OverviewTable(state = state, modifier = Modifier.weight(1f))
                } else {
                    EntryList(
                        state = state,
                        onEntryClick = onEntryClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showCustomRange) {
        CustomRangeDialog(
            initial = state.range,
            onDismiss = { showCustomRange = false },
            onConfirm = { range ->
                showCustomRange = false
                onRangeSelected(range)
            },
        )
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
        // Only entries in the range on screen are loaded, so this catches the
        // common case early. Entries from other months are still caught by the
        // server, whose refusal appears in the "That didn't work" dialog.
        // Deleted entries no longer count, so they don't stand in the way.
        val entryCount = state.transactions.count {
            it.walletId == pendingDelete.id &&
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

    if (state.notice != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text("Done") },
            text = { Text(state.notice) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) { Text("OK") }
            },
        )
    }
}

/** "‹ October 2026 ▾ ›" — arrows step through periods, the label opens the choices. */
@Composable
private fun RangeBar(
    range: DateRange,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (DateRange) -> Unit,
    onCustomClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = { menuOpen = true }) {
                Text(
                    text = "${range.label} ▾",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                val today = LocalDate.now()
                listOf(
                    "This week" to DateRange.week(today),
                    "This month" to DateRange.month(today),
                    "Last month" to DateRange.month(today.minusMonths(1)),
                    "This year" to DateRange.year(today),
                ).forEach { (label, choice) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            onSelect(choice)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom range…") },
                    onClick = {
                        menuOpen = false
                        onCustomClick()
                    },
                )
            }
        }

        TextButton(onClick = onNext) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initial: DateRange,
    onDismiss: () -> Unit,
    onConfirm: (DateRange) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start.toPickerMillis(),
        initialSelectedEndDateMillis = initial.end.toPickerMillis(),
    )
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (start != null && end != null) {
                        onConfirm(DateRange.custom(start.toPickerDate(), end.toPickerDate()))
                    }
                },
                enabled = start != null && end != null,
            ) {
                Text("Show")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
        )
    }
}

// The date picker works in milliseconds at midnight UTC, whatever the
// phone's time zone, so converting through UTC keeps the chosen day intact.
internal fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toPickerDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun WalletStrip(
    wallets: List<WalletResponse>,
    selectedWalletId: Int?,
    onWalletClick: (Int) -> Unit,
    onAddWalletClick: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
            // The cards always show what is in each wallet right now, whatever
            // range is chosen. The Overview tab shows balances for the range.
            Text(
                text = "${wallet.currency} · now",
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
    // Scrollable, so "All Transactions" keeps its full name. Four fixed-width
    // tabs on a narrow phone would cut it short.
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        edgePadding = 0.dp,
    ) {
        EntryFilter.entries.forEach { filter ->
            Tab(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                text = { Text(filter.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun EntryList(
    state: TrackerUiState,
    onEntryClick: (TransactionResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.visibleTransactions

    if (entries.isEmpty()) {
        // The heading always names the tab, so Expenses never says anything
        // about wallets. A missing wallet is explained underneath instead.
        CenteredMessage(modifier) {
            Text(
                text = when (state.filter) {
                    EntryFilter.All, EntryFilter.Overview -> "No transactions"
                    EntryFilter.Expenses -> "No expenses"
                    EntryFilter.Income -> "No income"
                } + " in ${state.range.label}",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.wallets.isEmpty()) {
                    "Add a wallet above first — your cash, a bank account, an " +
                            "e-wallet. Its opening balance becomes its first entry."
                } else {
                    "Use the arrows, or tap the dates above, to look at another period."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // The server sends entries newest first, and grouping keeps that order,
    // so each day's heading sits above that day's entries.
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
                    onClick = { onEntryClick(entry) },
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
    onClick: () -> Unit,
) {
    val title = entryTitle(entry)
    val details = listOfNotNull(
        entry.walletName.takeIf { showWallet },
        entry.description?.takeIf { it != title },
    ).joinToString(" · ")

    // A deleted entry stays in the list for the record, greyed out and struck
    // through, since it no longer counts towards any balance.
    val titleColor = if (entry.isDeleted) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color.Unspecified
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping an entry opens its details.
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Shrinks a long title instead of pushing the tag off the row.
                    modifier = Modifier.weight(1f, fill = false),
                )
                EntryTag(entry = entry, modifier = Modifier.padding(start = 8.dp))
            }
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
            text = amountText(entry, showCurrency),
            style = MaterialTheme.typography.bodyLarge.withDeletedStyle(entry),
            color = amountColor(entry),
        )
    }
}

/**
 * One row per wallet for the chosen range. For a range that has already
 * ended, the balance when it ended and the balance today are different
 * figures, so both are shown. Otherwise they are the same, and only Current
 * Balance appears. Wider than a phone held upright, so the table scrolls
 * sideways; the explanation below it does not.
 */
@Composable
private fun OverviewTable(
    state: TrackerUiState,
    modifier: Modifier = Modifier,
) {
    val summary = state.summary

    if (summary == null) {
        CenteredMessage(modifier) { CircularProgressIndicator() }
        return
    }

    if (summary.wallets.isEmpty()) {
        CenteredMessage(modifier) {
            Text(
                text = "No wallets in ${state.range.label}",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val rangeHasEnded = state.range.end.isBefore(LocalDate.now())
    val currentBalances = state.wallets.associate { it.id to it.balance }

    val headers = buildList {
        add("Wallet")
        add("Opening Balance")
        add("Income")
        add("Expenses")
        if (rangeHasEnded) add("Ending Balance")
        add("Current Balance")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // The bottom space keeps the Add button from covering the note.
            .padding(top = 8.dp, bottom = 88.dp),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            TableRow(cells = headers, isHeader = true)
            HorizontalDivider()

            summary.wallets.forEach { row ->
                TableRow(
                    cells = balanceCells(
                        label = if (state.showsCurrency) {
                            "${row.walletName} (${row.currency})"
                        } else {
                            row.walletName
                        },
                        opening = row.opening,
                        income = row.income,
                        expenses = row.expenses,
                        ending = row.ending,
                        current = currentBalances[row.walletId] ?: row.ending,
                        rangeHasEnded = rangeHasEnded,
                    ),
                    isBold = row.walletId == state.selectedWalletId,
                )
                HorizontalDivider()
            }

            // One total per currency: pesos and dollars are never added together.
            summary.totals.forEach { total ->
                val currentTotal = summary.wallets
                    .filter { it.currency == total.currency }
                    .sumOf { row ->
                        (currentBalances[row.walletId] ?: row.ending).toBigDecimalOrNull()
                            ?: BigDecimal.ZERO
                    }

                TableRow(
                    cells = balanceCells(
                        label = if (summary.totals.size > 1) "Total ${total.currency}" else "Total",
                        opening = total.opening,
                        income = total.income,
                        expenses = total.expenses,
                        ending = total.ending,
                        current = currentTotal.toPlainString(),
                        rangeHasEnded = rangeHasEnded,
                    ),
                    isBold = true,
                )
            }
        }

        Text(
            text = if (rangeHasEnded) {
                "Opening Balance is what each wallet held at the start of " +
                        "${state.range.start.asDayLabel()}, and Ending Balance what it " +
                        "held at the end of ${state.range.end.asDayLabel()}. " +
                        "Opening + Income − Expenses = Ending. Current Balance is what " +
                        "it holds today."
            } else {
                "Opening Balance is what each wallet held at the start of " +
                        "${state.range.start.asDayLabel()}. " +
                        "Opening + Income − Expenses = Current Balance."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
    }
}

/** The cells for one table row, with the Ending Balance column only for a finished range. */
private fun balanceCells(
    label: String,
    opening: String,
    income: String,
    expenses: String,
    ending: String,
    current: String,
    rangeHasEnded: Boolean,
): List<String> = buildList {
    add(label)
    add(formatMoney(opening))
    add(dashIfZero(income))
    add(dashIfZero(expenses))
    if (rangeHasEnded) {
        add(formatMoney(ending))
        add(formatMoney(current))
    } else {
        // For a range that reaches today, its ending balance is the current one.
        add(formatMoney(ending))
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    isHeader: Boolean = false,
    isBold: Boolean = false,
) {
    Row(
        modifier = Modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        cells.forEachIndexed { index, text ->
            val isAmount = index > 0
            Text(
                text = text,
                modifier = Modifier.width(if (isAmount) AmountColumnWidth else WalletColumnWidth),
                textAlign = if (isAmount) TextAlign.End else TextAlign.Start,
                style = if (isHeader) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isBold) FontWeight.Bold else null,
                color = when {
                    isHeader -> MaterialTheme.colorScheme.onSurfaceVariant
                    // Any balance below zero, in whichever column it appears.
                    isAmount && text.startsWith("−") -> MaterialTheme.colorScheme.error
                    else -> Color.Unspecified
                },
                // Headers such as "Current Balance" may wrap onto two lines.
                maxLines = if (isHeader) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Income and expense columns read more easily with a dash where nothing happened. */
private fun dashIfZero(amount: String): String =
    if ((amount.toBigDecimalOrNull()?.signum() ?: 0) == 0) "—" else formatMoney(amount)

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