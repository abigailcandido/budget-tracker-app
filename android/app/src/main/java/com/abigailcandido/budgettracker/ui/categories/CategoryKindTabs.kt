package com.abigailcandido.budgettracker.ui.categories

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.abigailcandido.budgettracker.data.remote.CategoryKind

val CategoryKind.tabLabel: String
    get() = when (this) {
        CategoryKind.Expense -> "Expenses"
        CategoryKind.Income -> "Income"
    }

val CategoryKind.singular: String
    get() = when (this) {
        CategoryKind.Expense -> "expense"
        CategoryKind.Income -> "income"
    }

val CategoryKind.groupLabel: String
    get() = when (this) {
        CategoryKind.Expense -> "Expense categories"
        CategoryKind.Income -> "Income categories"
    }

/** The Expenses / Income tabs at the top of both category lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryKindTabs(
    selected: CategoryKind,
    onSelect: (CategoryKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, modifier = modifier) {
        CategoryKind.entries.forEach { kind ->
            Tab(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                text = { Text(kind.tabLabel) },
            )
        }
    }
}