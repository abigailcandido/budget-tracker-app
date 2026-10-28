package com.abigailcandido.budgettracker.ui.trackers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import java.time.LocalDate

/** Which chooser is open on the page, if any. */
private enum class Picker { Wallet, Category, Subcategory, Date }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    form: EntryForm,
    wallets: List<WalletResponse>,
    onTypeChange: (EntryType) -> Unit,
    onWalletChange: (Int) -> Unit,
    onCategoryChange: (Int) -> Unit,
    onSubcategoryChange: (Int) -> Unit,
    onOtherNameChange: (String) -> Unit,
    onUseCategoryMatch: () -> Unit,
    onAddToCategoriesChange: (Boolean) -> Unit,
    onWithSubcategoryChange: (Boolean) -> Unit,
    onSubcategoryNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onNoteChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onRetryCategories: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The back gesture takes the same route as Cancel, so it is ignored while
    // a save is on its way, exactly like the Cancel button.
    BackHandler(onBack = onCancel)

    var openPicker by remember { mutableStateOf<Picker?>(null) }

    val mixedCurrencies = wallets.map { it.currency }.distinct().size > 1
    fun walletLabel(wallet: WalletResponse): String =
        if (mixedCurrencies) "${wallet.name} (${wallet.currency})" else wallet.name

    // A correction can only move to a wallet of the same currency.
    val walletChoices = if (form.currency == null) {
        wallets
    } else {
        wallets.filter { it.currency == form.currency }
    }
    val wallet = wallets.firstOrNull { it.id == form.walletId }
    val currency = wallet?.currency ?: form.currency.orEmpty()
    val selectedCategory = form.selectedCategory
    val selectedSubcategory = selectedCategory?.subcategories?.firstOrNull { it.id == form.subcategoryId }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            form.isNew -> "Add transaction"
                            form.isOpening -> "Edit opening balance"
                            form.type == EntryType.Income -> "Edit income"
                            else -> "Edit expense"
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancel, enabled = !form.isBusy) { Text("Cancel") }
                },
            )
        },
        bottomBar = {
            // Pinned at the bottom and lifted above the keyboard, so Save stays
            // within reach while typing the amount or the note.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = form.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (form.isSaving) "Saving…" else "Save")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Only a new entry chooses between expense and income. A correction
            // keeps its type, which the page title names instead.
            if (form.isNew && !form.isOpening) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EntryType.Expense to "Expense", EntryType.Income to "Income")
                        .forEach { (type, label) ->
                            FilterChip(
                                selected = form.type == type,
                                onClick = { onTypeChange(type) },
                                label = { Text(label) },
                                enabled = !form.isBusy,
                            )
                        }
                }
            }

            OutlinedTextField(
                value = form.amount,
                onValueChange = onAmountChange,
                label = { Text("Amount") },
                prefix = { Text("$currency ") },
                placeholder = { Text("0.00") },
                singleLine = true,
                enabled = !form.isBusy,
                supportingText = if (form.isOpening) {
                    { Text("A minus sign means the wallet started out owing money.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            PickerField(
                label = "Wallet",
                value = wallet?.let { walletLabel(it) }.orEmpty(),
                // An opening balance belongs to the wallet it opened.
                enabled = !form.isBusy && !form.isOpening,
                onClick = { openPicker = Picker.Wallet },
                supportingText = if (form.isOpening) {
                    { Text("An opening balance stays with its wallet.") }
                } else {
                    null
                },
            )

            if (!form.isOpening) {
                CategorySection(
                    form = form,
                    selectedCategory = selectedCategory,
                    selectedSubcategory = selectedSubcategory,
                    onOpenCategory = { openPicker = Picker.Category },
                    onOpenSubcategory = { openPicker = Picker.Subcategory },
                    onOtherNameChange = onOtherNameChange,
                    onUseCategoryMatch = onUseCategoryMatch,
                    onAddToCategoriesChange = onAddToCategoriesChange,
                    onWithSubcategoryChange = onWithSubcategoryChange,
                    onSubcategoryNameChange = onSubcategoryNameChange,
                    onRetry = onRetryCategories,
                )
            }

            PickerField(
                label = "Date",
                value = displayDate(form.date.toString()),
                enabled = !form.isBusy,
                onClick = { openPicker = Picker.Date },
            )

            OutlinedTextField(
                value = form.note,
                onValueChange = onNoteChange,
                label = { Text("Note (optional)") },
                enabled = !form.isBusy,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!form.isNew) {
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason for the change (optional)") },
                    placeholder = { Text("Such as Wrong amount") },
                    supportingText = { Text("Kept in the transaction's history.") },
                    enabled = !form.isBusy,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (form.error != null) {
                Text(
                    text = form.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    when (openPicker) {
        Picker.Wallet -> OptionDialog(
            title = "Choose a wallet",
            options = walletChoices.map { it.id to walletLabel(it) },
            selectedId = form.walletId,
            onSelect = { id ->
                openPicker = null
                onWalletChange(id)
            },
            onDismiss = { openPicker = null },
        )

        // Both lists end with Others, for anything that has no category yet.
        Picker.Category -> OptionDialog(
            title = if (form.type == EntryType.Income) "Income category" else "Expense category",
            options = form.availableCategories.map { it.id to it.name } + (OTHERS_CHOICE to OTHERS_LABEL),
            selectedId = form.topCategoryId,
            onSelect = { id ->
                openPicker = null
                onCategoryChange(id)
            },
            onDismiss = { openPicker = null },
        )

        Picker.Subcategory -> OptionDialog(
            title = selectedCategory?.name ?: "Sub-category",
            options = selectedCategory?.subcategories.orEmpty().map { it.id to it.name } +
                    (OTHERS_CHOICE to OTHERS_LABEL),
            selectedId = form.subcategoryId,
            onSelect = { id ->
                openPicker = null
                onSubcategoryChange(id)
            },
            onDismiss = { openPicker = null },
        )

        Picker.Date -> EntryDateDialog(
            initial = form.date,
            onDismiss = { openPicker = null },
            onConfirm = { date ->
                openPicker = null
                onDateChange(date)
            },
        )

        null -> Unit
    }
}

/**
 * The Category field, the Sub-category field under it when the chosen
 * category has subcategories, and the "What is it?" field when either is
 * Others.
 */
@Composable
private fun CategorySection(
    form: EntryForm,
    selectedCategory: CategoryOption?,
    selectedSubcategory: SubcategoryOption?,
    onOpenCategory: () -> Unit,
    onOpenSubcategory: () -> Unit,
    onOtherNameChange: (String) -> Unit,
    onUseCategoryMatch: () -> Unit,
    onAddToCategoriesChange: (Boolean) -> Unit,
    onWithSubcategoryChange: (Boolean) -> Unit,
    onSubcategoryNameChange: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val kindWord = if (form.type == EntryType.Income) "income" else "expense"

    when {
        form.isLoadingCategories -> PickerField(
            label = "Category",
            value = "Loading categories…",
            enabled = false,
            onClick = {},
        )

        form.categoryLoadError != null -> Column {
            Text(
                text = form.categoryLoadError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("Try again") }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PickerField(
                label = "Category",
                value = when {
                    form.topCategoryId == OTHERS_CHOICE -> OTHERS_LABEL
                    selectedCategory != null -> selectedCategory.name
                    else -> "Choose a category"
                },
                enabled = !form.isBusy,
                onClick = onOpenCategory,
                supportingText = if (form.availableCategories.isEmpty()) {
                    {
                        Text(
                            "No $kindWord categories are switched on in this tracker " +
                                    "yet, so only Others is offered. Add or switch some " +
                                    "on from Categories on the tracker screen."
                        )
                    }
                } else {
                    null
                },
            )
            // Only a category with subcategories asks for one of them.
            if (selectedCategory != null && selectedCategory.hasSubcategories) {
                PickerField(
                    label = "Sub-category",
                    value = when {
                        form.subcategoryId == OTHERS_CHOICE -> OTHERS_LABEL
                        selectedSubcategory != null -> selectedSubcategory.name
                        else -> "Choose a sub-category"
                    },
                    enabled = !form.isBusy,
                    onClick = onOpenSubcategory,
                )
            }
            if (form.isOthersChosen) {
                OtherNameField(
                    form = form,
                    onValueChange = onOtherNameChange,
                    onUseCategoryMatch = onUseCategoryMatch,
                    onAddToCategoriesChange = onAddToCategoriesChange,
                    onWithSubcategoryChange = onWithSubcategoryChange,
                    onSubcategoryNameChange = onSubcategoryNameChange,
                )
            }
        }
    }
}

/**
 * What an entry under Others was. A name that is already a category, in any
 * mix of capitals, is refused, with a button to file the entry there instead.
 * "Add to my categories" files it under a new category of that name instead
 * of under Others; at the top level, that category can hold a sub-category.
 */
@Composable
private fun OtherNameField(
    form: EntryForm,
    onValueChange: (String) -> Unit,
    onUseCategoryMatch: () -> Unit,
    onAddToCategoriesChange: (Boolean) -> Unit,
    onWithSubcategoryChange: (Boolean) -> Unit,
    onSubcategoryNameChange: (String) -> Unit,
) {
    // Nothing is called a problem until something has been typed.
    val problem = form.otherNameProblem.takeIf { form.otherName.isNotBlank() }
    val match = form.otherNameMatch
    val newCategory = form.newCategoryLabel

    Column {
        OutlinedTextField(
            value = form.otherName,
            onValueChange = onValueChange,
            label = { Text("What is it?") },
            placeholder = {
                Text(if (form.type == EntryType.Income) "Such as Gift" else "Such as Pet food")
            },
            singleLine = true,
            enabled = !form.isBusy,
            isError = problem != null,
            supportingText = {
                Text(
                    when {
                        problem != null -> problem
                        newCategory != null -> "Saved under a category of its own: $newCategory."
                        form.addToCategories -> "Saved under a category of its own."
                        else -> "Saved under Others. You can add it to your categories " +
                                "later, from its details."
                    }
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        if (match != null) {
            TextButton(onClick = onUseCategoryMatch, enabled = !form.isBusy) {
                Text("Use ${match.label} instead")
            }
        }

        SwitchRow(
            label = "Add to my categories",
            checked = form.addToCategories,
            enabled = !form.isBusy,
            onCheckedChange = onAddToCategoriesChange,
        )

        // Only a new top-level category can hold a sub-category; one under
        // "Bills › Others" is a sub-category already.
        if (form.addToCategories && form.canHaveSubcategory) {
            SwitchRow(
                label = "Give it a sub-category",
                checked = form.withSubcategory,
                enabled = !form.isBusy,
                onCheckedChange = onWithSubcategoryChange,
            )
            if (form.withSubcategory) {
                val subProblem = form.subcategoryNameProblem
                    .takeIf { form.subcategoryName.isNotBlank() }
                OutlinedTextField(
                    value = form.subcategoryName,
                    onValueChange = onSubcategoryNameChange,
                    label = { Text("Sub-category") },
                    placeholder = { Text("Such as Food") },
                    singleLine = true,
                    enabled = !form.isBusy,
                    isError = subProblem != null,
                    supportingText = if (subProblem != null) {
                        { Text(subProblem) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A label with an on/off switch at the end of the row. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A field that looks like the others but opens a chooser when tapped. */
@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    supportingText: (@Composable () -> Unit)? = null,
) {
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Text("▾") },
            supportingText = supportingText,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only text field still keeps taps for itself, so a clear
        // layer on top catches them instead.
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onClick),
            )
        }
    }
}

/** A short list to pick one item from. Tapping an item chooses it and closes. */
@Composable
private fun OptionDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selectedId, onClick = { onSelect(id) })
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val todayMillis = remember(today) { today.toPickerMillis() }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        selectableDates = remember(todayMillis) {
            // Entries record what has already happened, so no day after today.
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= todayMillis

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        },
    )
    val selected = pickerState.selectedDateMillis

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (selected != null) onConfirm(selected.toPickerDate()) },
                enabled = selected != null,
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = pickerState)
    }
}