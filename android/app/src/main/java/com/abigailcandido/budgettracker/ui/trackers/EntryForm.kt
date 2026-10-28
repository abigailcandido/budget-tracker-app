package com.abigailcandido.budgettracker.ui.trackers

import com.abigailcandido.budgettracker.data.remote.CategoryKind
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TrackerCategoryResponse
import com.abigailcandido.budgettracker.data.remote.categoryKind
import java.time.LocalDate

/** The longest note or reason the server keeps. */
const val NOTE_MAX_LENGTH = 255

/** The longest "What is it?" name the server keeps for an Others entry. */
const val OTHER_NAME_MAX_LENGTH = 100

/**
 * The Others choice at the end of the Category and Sub-category lists. It is
 * not a real id: the Others category it stands for is fetched from the
 * server, and made there if need be, only when the entry is saved.
 */
const val OTHERS_CHOICE = -1

const val OTHERS_LABEL = "Others"

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
 * subcategories is chosen next, or under its Others.
 */
data class CategoryOption(
    val id: Int,
    val name: String,
    val kind: CategoryKind,
    val hasSubcategories: Boolean = false,
    /** Its switched-on subcategories, not counting its Others. */
    val subcategories: List<SubcategoryOption> = emptyList(),
)

/** An existing category whose name was typed as an Others name. */
data class CategoryMatch(
    val topCategoryId: Int,
    /** Null when the match is a top-level category. */
    val subcategoryId: Int?,
    /** "Groceries" or "Bills › Electricity". */
    val label: String,
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
    /** The Category field: a top-level category's id, or OTHERS_CHOICE. */
    val topCategoryId: Int? = null,
    /** The Sub-category field, under a category that has them: an id, or OTHERS_CHOICE. */
    val subcategoryId: Int? = null,
    /** What an entry under Others was, such as "Pet food". */
    val otherName: String = "",
    /**
     * The "Add to my categories" switch under Others. Off, the entry is filed
     * under Others with its name; on, it is filed under a category of that name.
     */
    val addToCategories: Boolean = false,
    /** Only for a new top-level category: whether it holds a sub-category too. */
    val withSubcategory: Boolean = false,
    /** That sub-category's name, such as "Food" under a new "Pets". */
    val subcategoryName: String = "",
    /**
     * The category the entry had when the page opened. It stays on offer even
     * if it has since been switched off, so correcting an old amount never
     * forces a new category on the entry.
     */
    val originalCategoryId: Int? = null,
    val amount: String = "",
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    /** Why the entry is being corrected. Optional, and only asked when correcting. */
    val reason: String = "",
    val categories: List<CategoryOption> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val categoryLoadError: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean
        get() = editingId == null

    /** An opening balance keeps its wallet and has no category. */
    val isOpening: Boolean
        get() = type == EntryType.Opening

    val isBusy: Boolean
        get() = isSaving

    /** Expense categories for an expense, income categories for an income. */
    val availableCategories: List<CategoryOption>
        get() = categoriesFor(type)

    /** The top-level category chosen in the Category field, if it is a real one. */
    val selectedCategory: CategoryOption?
        get() = availableCategories.firstOrNull { it.id == topCategoryId }

    /** Whether the entry is going under Others, at the top level or under a category. */
    val isOthersChosen: Boolean
        get() = topCategoryId == OTHERS_CHOICE ||
                (selectedCategory?.hasSubcategories == true && subcategoryId == OTHERS_CHOICE)

    /** The parent whose Others is chosen, or null for the top-level Others. */
    val othersParentId: Int?
        get() = if (topCategoryId == OTHERS_CHOICE) null else selectedCategory?.id

    /**
     * Whether a new category made from the Others name can hold a sub-category.
     * Only at the top level: under "Bills › Others" the name is already a
     * sub-category, and there is no third level.
     */
    val canHaveSubcategory: Boolean
        get() = topCategoryId == OTHERS_CHOICE

    /** Whether the entry will be filed under a new category rather than under Others. */
    val isMakingCategory: Boolean
        get() = isOthersChosen && addToCategories

    private val isMakingSubcategory: Boolean
        get() = isMakingCategory && canHaveSubcategory && withSubcategory

    /**
     * The category the entry will be saved under when "Add to my categories"
     * is on: "Pets", "Pets › Food", or "Bills › Internet". Null otherwise.
     */
    val newCategoryLabel: String?
        get() {
            if (!isMakingCategory) return null
            val name = otherName.trim().ifEmpty { return null }
            val parentName = selectedCategory?.name.takeIf { !canHaveSubcategory }
            val subName = subcategoryName.trim()
            return when {
                parentName != null -> "$parentName › $name"
                isMakingSubcategory && subName.isNotEmpty() -> "$name › $subName"
                else -> name
            }
        }

    /** The real category the entry is filed under, when Others is not chosen. */
    val leafCategoryId: Int?
        get() {
            val chosen = selectedCategory ?: return null
            return when {
                !chosen.hasSubcategories -> chosen.id
                chosen.subcategories.any { it.id == subcategoryId } -> subcategoryId
                else -> null
            }
        }

    /**
     * An existing category the typed Others name already belongs to,
     * ignoring capitals and surrounding spaces. Its subcategories count too.
     */
    val otherNameMatch: CategoryMatch?
        get() {
            val typed = otherName.trim().lowercase()
            if (typed.isEmpty()) return null

            for (option in availableCategories) {
                if (option.name.lowercase() == typed) {
                    return CategoryMatch(option.id, null, option.name)
                }
                val sub = option.subcategories.firstOrNull { it.name.lowercase() == typed }
                if (sub != null) {
                    return CategoryMatch(option.id, sub.id, "${option.name} › ${sub.name}")
                }
            }
            return null
        }

    /** Why the new sub-category's name cannot be saved yet, or null when it can. */
    val subcategoryNameProblem: String?
        get() = when {
            !isMakingSubcategory -> null
            subcategoryName.isBlank() -> "Name the sub-category."
            isOthersName(subcategoryName) -> "Choose a name other than Others."
            else -> null
        }

    /** Why the Others name cannot be saved yet, or null when it can. */
    val otherNameProblem: String?
        get() = when {
            !isOthersChosen -> null
            otherName.isBlank() -> "Say what it was, so it can be given a category later."
            otherName.trim().equals(OTHERS_LABEL, ignoreCase = true) ->
                "Say what it was, rather than just Others."
            otherNameMatch != null -> "${otherNameMatch?.label} is already a category."
            else -> null
        }

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

    private val hasCompleteCategory: Boolean
        get() = if (isOthersChosen) {
            otherNameProblem == null && subcategoryNameProblem == null
        } else {
            leafCategoryId != null
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

/** Whether a category is an Others bucket, whatever capitals it was given. */
fun isOthersName(name: String): Boolean = name.trim().equals(OTHERS_LABEL, ignoreCase = true)

/**
 * Turns a tracker's categories into the two pickers' choices. Others buckets
 * are left out, since every list ends with its own Others choice. A top-level
 * category without subcategories is offered if it is switched on; one with
 * subcategories is offered if any of them is. keepId stays on offer whatever
 * its switch says.
 */
fun categoryOptions(
    categories: List<TrackerCategoryResponse>,
    keepId: Int?,
): List<CategoryOption> {
    val childrenByParent = categories
        .filter { it.parentId != null }
        .groupBy { it.parentId }

    return categories
        .filter { it.parentId == null && !isOthersName(it.name) }
        .mapNotNull { top ->
            if (!top.hasChildren) {
                if (top.isEnabled || top.id == keepId) {
                    CategoryOption(top.id, top.name, top.categoryKind)
                } else {
                    null
                }
            } else {
                val usable = childrenByParent[top.id].orEmpty()
                    .filter { it.isEnabled || it.id == keepId }

                if (usable.isEmpty()) {
                    null
                } else {
                    CategoryOption(
                        id = top.id,
                        name = top.name,
                        kind = top.categoryKind,
                        hasSubcategories = true,
                        subcategories = usable
                            .filter { !isOthersName(it.name) }
                            .map { SubcategoryOption(it.id, it.name) }
                            .sortedBy { it.name.lowercase() },
                    )
                }
            }
        }
        .sortedBy { it.name.lowercase() }
}

/**
 * Where the two fields start when correcting an entry: its own category, its
 * subcategory's parent and the subcategory, or Others at either level.
 */
fun choiceFor(
    categoryId: Int?,
    categories: List<TrackerCategoryResponse>,
): Pair<Int?, Int?> {
    val category = categories.firstOrNull { it.id == categoryId } ?: return null to null
    val parentId = category.parentId

    return when {
        parentId == null && isOthersName(category.name) -> OTHERS_CHOICE to null
        parentId == null -> category.id to null
        isOthersName(category.name) -> parentId to OTHERS_CHOICE
        else -> parentId to category.id
    }
}