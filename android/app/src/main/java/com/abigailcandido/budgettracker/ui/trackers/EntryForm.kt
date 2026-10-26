package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.CategoryKind
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TrackerCategoryResponse
import com.abigailcandido.budgettracker.data.remote.categoryKind
import java.time.LocalDate

/** The longest note the server keeps. */
const val NOTE_MAX_LENGTH = 255

/**
 * Up to ten digits before the point and two after, which is the most the
 * server stores. An empty field and a trailing point are allowed while typing.
 */
private val AMOUNT_PATTERN = Regex("^\\d{0,10}(\\.\\d{0,2})?$")

/** The same, with an optional leading minus: a wallet can open owing money. */
private val OPENING_AMOUNT_PATTERN = Regex("^-?\\d{0,10}(\\.\\d{0,2})?$")

/** One choice in the category picker. */
data class CategoryOption(
    val id: Int,
    /** "Bills › Electricity" for a subcategory, or just "Groceries". */
    val label: String,
    val kind: CategoryKind,
)

/**
 * The add-or-edit page's form. It lives in the tracker screen's ViewModel, so
 * saving refreshes the very screen the user goes back to.
 */
data class EntryForm(
    /** The entry being corrected, or null for a new one. */
    val editingId: Int? = null,
    val type: EntryType = EntryType.Expense,
    val walletId: Int? = null,
    /**
     * Only set when correcting. An entry can only move to a wallet of the
     * same currency, so the wallet choices are limited to this one.
     */
    val currency: String? = null,
    val categoryId: Int? = null,
    /**
     * The category the entry had when the page opened. It stays on offer even
     * if it has since been switched off, so correcting an old amount never
     * forces a new category on the entry.
     */
    val originalCategoryId: Int? = null,
    val amount: String = "",
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val categories: List<CategoryOption> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val categoryLoadError: String? = null,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val isConfirmingDelete: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean
        get() = editingId == null

    /** An opening balance keeps its wallet and has no category. */
    val isOpening: Boolean
        get() = type == EntryType.Opening

    val isBusy: Boolean
        get() = isSaving || isDeleting

    /** Expense categories for an expense, income categories for an income. */
    val availableCategories: List<CategoryOption>
        get() = categoriesFor(type)

    fun categoriesFor(entryType: EntryType): List<CategoryOption> {
        val kind = when (entryType) {
            EntryType.Expense -> CategoryKind.Expense
            EntryType.Income -> CategoryKind.Income
            EntryType.Opening -> return emptyList()
        }
        return categories.filter { it.kind == kind }
    }

    fun acceptsAmount(value: String): Boolean =
        if (isOpening) OPENING_AMOUNT_PATTERN.matches(value) else AMOUNT_PATTERN.matches(value)

    val canSave: Boolean
        get() {
            if (isBusy) return false
            val value = amount.toBigDecimalOrNull() ?: return false
            return if (isOpening) {
                // An opening balance of zero is deleted, not saved.
                value.signum() != 0
            } else {
                value.signum() > 0 &&
                        walletId != null &&
                        availableCategories.any { it.id == categoryId }
            }
        }
}

/**
 * Turns a tracker's categories into picker choices. Only switched-on
 * categories with nothing under them are offered, because entries attach to
 * those alone. Sorting by the full label keeps each parent's subcategories
 * together. keepId stays on offer whatever its switch says.
 */
fun categoryOptions(
    categories: List<TrackerCategoryResponse>,
    keepId: Int?,
): List<CategoryOption> {
    val namesById = categories.associate { it.id to it.name }

    return categories
        .filter { !it.hasChildren && (it.isEnabled || it.id == keepId) }
        .map { category ->
            val parentName = category.parentId?.let { namesById[it] }
            CategoryOption(
                id = category.id,
                label = if (parentName != null) "$parentName › ${category.name}" else category.name,
                kind = category.categoryKind,
            )
        }
        .sortedBy { it.label.lowercase() }
}