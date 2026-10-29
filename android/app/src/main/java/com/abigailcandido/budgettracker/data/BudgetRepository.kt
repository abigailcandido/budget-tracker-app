package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.BudgetApi
import com.abigailcandido.budgettracker.data.remote.BudgetResponse
import com.abigailcandido.budgettracker.data.remote.ExpectedAmount
import com.abigailcandido.budgettracker.data.remote.ExpectedAmountRequest
import java.time.LocalDate
import java.time.YearMonth

class BudgetRepository(
    private val tokenStore: TokenStore,
    private val api: BudgetApi = BudgetApi(),
) {

    /**
     * Expected and actual amounts per category from start to end, both days
     * included, in one currency. A null currency lets the server choose.
     */
    suspend fun budget(
        trackerId: Int,
        start: LocalDate,
        end: LocalDate,
        currency: String?,
    ): ApiResult<BudgetResponse> =
        tokenStore.withToken { token ->
            api.budget(token, trackerId, start.toString(), end.toString(), currency)
        }

    /**
     * Sets a category's expected amount per month from a month on, until a
     * later change. A null or blank amount clears it from that month on.
     */
    suspend fun setExpected(
        trackerId: Int,
        categoryId: Int,
        currency: String,
        month: YearMonth,
        amount: String?,
    ): ApiResult<ExpectedAmount> =
        tokenStore.withToken { token ->
            api.setExpected(
                token,
                trackerId,
                categoryId,
                ExpectedAmountRequest(
                    currency = currency,
                    startsOn = month.atDay(1).toString(),
                    amount = amount?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
}