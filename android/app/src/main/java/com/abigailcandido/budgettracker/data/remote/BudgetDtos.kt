package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A category's expected amount per month, from one month on, as the server keeps it. */
@Serializable
data class ExpectedAmount(
    @SerialName("category_id") val categoryId: Int,
    val currency: String,
    /** The first day of the month it applies from, such as "2026-10-01". */
    @SerialName("starts_on") val startsOn: String,
    /** Empty when the expected amount was cleared from that month on. */
    val amount: String? = null,
)

@Serializable
data class ExpectedAmountRequest(
    val currency: String,
    /** The first day of the month the amount applies from. */
    @SerialName("starts_on") val startsOn: String,
    // Left out to clear the expected amount from that month on.
    val amount: String? = null,
)

/**
 * One category on the Budget tab. A category with subcategories has their
 * rows, and its figures are theirs added up.
 */
@Serializable
data class BudgetRow(
    @SerialName("category_id") val categoryId: Int,
    val name: String,
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    /** Empty when no month in the dates has an expected amount. */
    val expected: String? = null,
    val actual: String,
    /** Below zero is the bad side: more spent, or less received, than expected. */
    val difference: String? = null,
    /** Every change to the monthly amount, oldest first. */
    val amounts: List<ExpectedAmount> = emptyList(),
    val subcategories: List<BudgetRow> = emptyList(),
)

@Serializable
data class BudgetTotal(
    val expected: String? = null,
    val actual: String,
    val difference: String? = null,
)

@Serializable
data class BudgetTable(
    val kind: String,
    val rows: List<BudgetRow>,
    val total: BudgetTotal,
)

@Serializable
data class BudgetResponse(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    /** The currency every figure is in. Empty for a tracker with no wallets. */
    val currency: String? = null,
    /** The currencies the tracker's wallets use, the most used first. */
    val currencies: List<String> = emptyList(),
    val expense: BudgetTable,
    val income: BudgetTable,
)

/** Only a category without subcategories, or a subcategory, has amounts of its own. */
val BudgetRow.hasSubcategories: Boolean
    get() = subcategories.isNotEmpty()