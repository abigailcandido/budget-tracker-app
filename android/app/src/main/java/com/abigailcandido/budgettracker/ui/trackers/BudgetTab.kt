package com.abigailcandido.budgettracker.ui.trackers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.BudgetRow
import com.abigailcandido.budgettracker.data.remote.BudgetTable
import com.abigailcandido.budgettracker.data.remote.BudgetTotal
import com.abigailcandido.budgettracker.data.remote.hasSubcategories

// The four columns share the width between them, the Category column a little
// more than each amount, so the whole table fits a phone held upright.
private const val CategoryColumnWeight = 1.2f
private const val AmountColumnWeight = 1f

/**
 * The Budget tab: for each category, what was expected over the dates
 * showing, what actually went out or came in, and the difference. One table
 * for expenses and one for income, each followed by a table for every
 * category that has subcategories. Tapping a category that holds amounts of
 * its own opens the dialog that sets its amount per month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetTab(
    state: TrackerUiState,
    onCurrencySelected: (String) -> Unit,
    onRowClick: (BudgetRow, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val budget = state.budget

    if (budget == null) {
        BudgetMessage(modifier) { CircularProgressIndicator() }
        return
    }

    if (budget.currency == null) {
        BudgetMessage(modifier) {
            Text(
                text = "No wallets yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a wallet above first. The Budget tab compares what you " +
                        "expect with what goes in and out of your wallets.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Where each sub-category table starts, so tapping its category in the
    // main table can scroll down to it.
    val tableTops = remember { mutableStateMapOf<Int, Int>() }
    val scrollToTable: (BudgetRow) -> Unit = { parent ->
        val top = tableTops[parent.categoryId]
        if (top != null) scope.launch { scrollState.animateScrollTo(top) }
    }
    // Expenses or income, one at a time.
    var showsIncome by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            // The bottom space keeps the Add button from covering the last row.
            .padding(top = 8.dp, bottom = 88.dp),
    ) {
        // Pesos and dollars are never added together, so each currency has its own budget.
        if (budget.currencies.size > 1) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                budget.currencies.forEach { currency ->
                    FilterChip(
                        selected = currency == budget.currency,
                        onClick = { onCurrencySelected(currency) },
                        label = { Text(currency) },
                    )
                }
            }
        }

        if (state.selectedWallet != null) {
            BudgetNote("The Budget tab counts every wallet in this tracker.")
        }

        SecondaryTabRow(
            selectedTabIndex = if (showsIncome) 1 else 0,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Tab(
                selected = !showsIncome,
                onClick = { showsIncome = false },
                text = { Text("Expenses") },
            )
            Tab(
                selected = showsIncome,
                onClick = { showsIncome = true },
                text = { Text("Income") },
            )
        }

        if (showsIncome) {
            BudgetSection(
                table = budget.income,
                emptyText = "No income categories are switched on in this tracker.",
                differenceNote = "Difference is actual minus expected. Below zero means " +
                        "less came in than expected.",
                onRowClick = onRowClick,
                onParentClick = scrollToTable,
                onTablePlaced = { categoryId, top -> tableTops[categoryId] = top },
            )
        } else {
            BudgetSection(
                table = budget.expense,
                emptyText = "No expense categories are switched on in this tracker.",
                differenceNote = "Difference is expected minus actual. Below zero means " +
                        "more was spent than expected.",
                onRowClick = onRowClick,
                onParentClick = scrollToTable,
                onTablePlaced = { categoryId, top -> tableTops[categoryId] = top },
            )
        }

        BudgetNote(
            "Expected is each month's amount, shared out by days when the dates " +
                    "cover only part of a month. Tap a category to set its amount per " +
                    "month. A category marked › has sub-categories: it adds theirs up, " +
                    "and tapping it goes to their table."
        )
    }
}

@Composable
private fun BudgetSection(
    table: BudgetTable,
    emptyText: String,
    differenceNote: String,
    onRowClick: (BudgetRow, String?) -> Unit,
    onParentClick: (BudgetRow) -> Unit,
    onTablePlaced: (Int, Int) -> Unit,
) {
    if (table.rows.isEmpty()) {
        BudgetNote(emptyText)
        return
    }

    Spacer(Modifier.height(8.dp))
    BudgetGrid(
        rows = table.rows,
        total = table.total,
        parentName = null,
        onRowClick = onRowClick,
        onParentClick = onParentClick,
    )
    BudgetNote(differenceNote)

    // Each category with sub-categories has its own table below. Where each
    // one starts is noted, so tapping the category above can scroll to it.
    table.rows.filter { it.hasSubcategories }.forEach { parent ->
        Text(
            text = parent.name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .onGloballyPositioned { coordinates ->
                    onTablePlaced(parent.categoryId, coordinates.positionInParent().y.roundToInt())
                },
        )
        BudgetGrid(
            rows = parent.subcategories,
            total = BudgetTotal(parent.expected, parent.actual, parent.difference),
            parentName = parent.name,
            onRowClick = onRowClick,
            onParentClick = null,
        )
    }
}
/** Category, Expected, Actual and Difference, all four on screen without scrolling sideways. */
@Composable
private fun BudgetGrid(
    rows: List<BudgetRow>,
    total: BudgetTotal,
    parentName: String?,
    onRowClick: (BudgetRow, String?) -> Unit,
    // Set for the main table, where a category with sub-categories leads to
    // their table further down.
    onParentClick: ((BudgetRow) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        BudgetCells(
            name = "Category",
            expected = "Expected",
            actual = "Actual",
            difference = "Difference",
            isHeader = true,
        )
        HorizontalDivider()

        rows.forEach { row ->
            // A category with sub-categories has no amount of its own to set.
            val canSet = !row.hasSubcategories
            BudgetCells(
                name = if (!canSet && onParentClick != null) "${row.name} ›" else row.name,
                expected = when {
                    row.expected != null -> formatMoney(row.expected)
                    canSet -> "Set"
                    else -> "—"
                },
                actual = actualText(row.actual),
                difference = row.difference?.let(::formatMoney) ?: "—",
                isMuted = !row.isEnabled,
                expectedIsAction = canSet && row.expected == null,
                onClick = when {
                    canSet -> {
                        { onRowClick(row, parentName) }
                    }
                    onParentClick != null -> {
                        { onParentClick(row) }
                    }
                    else -> null
                },
            )
            HorizontalDivider()
        }

        BudgetCells(
            name = "Total",
            expected = total.expected?.let(::formatMoney) ?: "—",
            actual = actualText(total.actual),
            difference = total.difference?.let(::formatMoney) ?: "—",
            isBold = true,
        )
    }
}

@Composable
private fun BudgetCells(
    name: String,
    expected: String,
    actual: String,
    difference: String,
    isHeader: Boolean = false,
    isBold: Boolean = false,
    isMuted: Boolean = false,
    expectedIsAction: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val typography = MaterialTheme.typography
    val style = if (isHeader) typography.labelMedium else typography.bodyMedium
    // Long amounts are set a size or two smaller, so they fit their column
    // whole instead of being cut off.
    fun amountStyle(text: String) = when {
        isHeader -> typography.labelMedium
        text.length > 12 -> typography.labelSmall
        text.length > 9 -> typography.bodySmall
        else -> typography.bodyMedium
    }
    val weight = if (isBold) FontWeight.Bold else null
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = style,
            fontWeight = weight,
            // A switched-off category only appears when something was filed under it.
            color = if (isHeader || isMuted) headerColor else Color.Unspecified,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(CategoryColumnWeight),
        )
        Text(
            text = expected,
            style = amountStyle(expected),
            fontWeight = weight,
            color = when {
                isHeader -> headerColor
                expectedIsAction -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .weight(AmountColumnWeight)
                .padding(start = 4.dp),
        )
        Text(
            text = actual,
            style = amountStyle(actual),
            fontWeight = weight,
            color = if (isHeader) headerColor else Color.Unspecified,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .weight(AmountColumnWeight)
                .padding(start = 4.dp),
        )
        Text(
            text = difference,
            style = amountStyle(difference),
            fontWeight = weight,
            color = when {
                isHeader -> headerColor
                // Below zero is always the bad side.
                difference.startsWith("−") -> MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .weight(AmountColumnWeight)
                .padding(start = 4.dp),
        )
    }
}

/** Actual amounts read more easily with a dash where nothing happened. */
private fun actualText(amount: String): String =
    if ((amount.toBigDecimalOrNull()?.signum() ?: 0) == 0) "—" else formatMoney(amount)

@Composable
private fun BudgetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun BudgetMessage(
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

/**
 * Sets a category's expected amount per month. It applies from the month
 * chosen here until a later change, so the months before keep theirs.
 */
@Composable
internal fun ExpectedAmountDialog(
    form: ExpectedAmountForm,
    onAmountChange: (String) -> Unit,
    onMonthStep: (Long) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = form.currentAmount
    val next = form.nextChange

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expected for ${form.label}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = onAmountChange,
                    label = { Text("Amount per month (${form.currency})") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    enabled = !form.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Starting from",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onMonthStep(-1) }, enabled = !form.isSaving) {
                        Text("‹")
                    }
                    Text(
                        text = form.month.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onMonthStep(1) }, enabled = !form.isSaving) {
                        Text("›")
                    }
                }

                Text(
                    text = buildString {
                        append(
                            if (current != null) {
                                "It is ${formatMoney(current)} a month in ${form.month.label()} now. "
                            } else {
                                "${form.month.label()} has no expected amount yet. "
                            }
                        )
                        append("A new amount applies from that month on")
                        if (next != null) {
                            append(", until ${next.month.label()}, which has its own")
                        }
                        append(". Earlier months keep theirs.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (form.error != null) {
                    Text(
                        text = form.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = form.canSave) {
                Text(if (form.isSaving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onClear, enabled = form.canClear) { Text("Clear") }
                }
                TextButton(onClick = onDismiss, enabled = !form.isSaving) { Text("Cancel") }
            }
        },
    )
}