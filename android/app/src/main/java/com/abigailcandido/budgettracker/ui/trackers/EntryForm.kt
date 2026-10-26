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

/** A choice in the Sub-category picker. */
data class SubcategoryOption(
    val id: Int,
    val name: String,
)

/**
 * A choice in the Category picker, which lists top-level categories only. One
 * with subcategories is a heading: the entry is filed under whichever of its
 * subcategories is chosen next.
 */
data class CategoryOption(
    val id: Int,
    val name: String,
    val kind: CategoryKind,
    val subcategories: List<SubcategoryOption> = emptyList(),
) {
    val hasSubcategories: Boolean
        get() = subcategories.isNotEmpty()
}

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
    /** The top-level category showing in the Category field. */
    val topCategoryId: Int? = null,
    /**
     * The category the entry is filed under: the top-level one itself, or
     * the subcategory chosen under it.
     */
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

    /** The top-level category chosen in the Category field, if it fits this kind. */
    val selectedCategory: CategoryOption?
        get() = availableCategories.firstOrNull { it.id == topCategoryId }

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

    /**
     * A category without subcategories is complete once chosen; one with
     * subcategories also needs one of them.
     */
    private val hasCompleteCategory: Boolean
        get() {
            val chosen = selectedCategory ?: return false
            return if (chosen.hasSubcategories) {
                chosen.subcategories.any { it.id == categoryId }
            } else {
                categoryId == chosen.id
            }
        }

    val canSave: Boolean
        get() {
            if (isBusy) return false
            val value = amount.toBigDecimalOrNull() ?: return false
            return if (isOpening) {
                // An opening balance of zero is deleted, not saved.
                value.signum() != 0
            } else {
                value.signum() > 0 && walletId != null && hasCompleteCategory
            }
        }
}

/**
 * Turns a tracker's categories into the two pickers' choices. A top-level
 * category without subcategories is offered if it is switched on. One with
 * subcategories is offered with its switched-on subcategories, and left out if
 * it has none. keepId stays on offer whatever its switch says.
 */
fun categoryOptions(
    categories: List<TrackerCategoryResponse>,
    keepId: Int?,
): List<CategoryOption> {
    val childrenByParent = categories
        .filter { it.parentId != null }
        .groupBy { it.parentId }

    return categories
        .filter { it.parentId == null }
        .mapNotNull { top ->
            if (!top.hasChildren) {
                if (top.isEnabled || top.id == keepId) {
                    CategoryOption(top.id, top.name, top.categoryKind)
                } else {
                    null
                }
            } else {
                val subcategories = childrenByParent[top.id].orEmpty()
                    .filter { it.isEnabled || it.id == keepId }
                    .map { SubcategoryOption(it.id, it.name) }
                    .sortedBy { it.name.lowercase() }

                if (subcategories.isEmpty()) {
                    null
                } else {
                    CategoryOption(top.id, top.name, top.categoryKind, subcategories)
                }
            }
        }
        .sortedBy { it.name.lowercase() }
}

/** The top-level category an entry's category sits under: itself, or its parent. */
fun List<CategoryOption>.topCategoryFor(categoryId: Int?): Int? {
    if (categoryId == null) return null
    return firstOrNull { option ->
        option.id == categoryId || option.subcategories.any { it.id == categoryId }
    }?.id
}