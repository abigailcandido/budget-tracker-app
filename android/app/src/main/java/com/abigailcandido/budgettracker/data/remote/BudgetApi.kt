package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody

class BudgetApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun budget(
        token: String,
        trackerId: Int,
        startDate: String,
        endDate: String,
        currency: String?,
    ): ApiResult<BudgetResponse> =
        callReturningBody("Could not load this tracker's budget") {
            client.get("trackers/$trackerId/budget") {
                bearer(token)
                parameter("start_date", startDate)
                parameter("end_date", endDate)
                // Left out when null, so the server uses the currency most wallets use.
                parameter("currency", currency)
            }
        }

    suspend fun setExpected(
        token: String,
        trackerId: Int,
        categoryId: Int,
        request: ExpectedAmountRequest,
    ): ApiResult<ExpectedAmount> =
        callReturningBody("Could not save the expected amount") {
            client.put("trackers/$trackerId/budget/$categoryId") {
                bearer(token)
                setBody(request)
            }
        }
}