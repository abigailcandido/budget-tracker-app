package com.abigailcandido.budgettracker.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class WalletApi(private val client: HttpClient = ApiClient.httpClient) {

    suspend fun list(token: String, trackerId: Int): ApiResult<List<WalletResponse>> =
        callReturningBody("Could not load this tracker's wallets") {
            client.get("trackers/$trackerId/wallets") { bearer(token) }
        }

    suspend fun create(
        token: String,
        trackerId: Int,
        name: String,
        currency: String,
        openingBalance: String,
        openingDate: String? = null,
    ): ApiResult<WalletResponse> =
        callReturningBody("Could not add the wallet") {
            client.post("trackers/$trackerId/wallets") {
                bearer(token)
                setBody(
                    WalletCreateRequest(
                        name = name,
                        currency = currency,
                        openingBalance = openingBalance,
                        openingDate = openingDate,
                    )
                )
            }
        }

    suspend fun rename(token: String, walletId: Int, name: String): ApiResult<WalletResponse> =
        callReturningBody("Could not rename the wallet") {
            client.patch("wallets/$walletId") {
                bearer(token)
                setBody(WalletNameRequest(name))
            }
        }

    suspend fun delete(token: String, walletId: Int): ApiResult<Unit> =
        callReturningNothing("Could not delete the wallet") {
            client.delete("wallets/$walletId") { bearer(token) }
        }
}