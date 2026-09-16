package com.abigailcandido.budgettracker.data

import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.WalletApi
import com.abigailcandido.budgettracker.data.remote.WalletResponse

class WalletRepository(
    private val tokenStore: TokenStore,
    private val api: WalletApi = WalletApi(),
) {

    suspend fun list(trackerId: Int): ApiResult<List<WalletResponse>> =
        tokenStore.withToken { token -> api.list(token, trackerId) }

    suspend fun create(
        trackerId: Int,
        name: String,
        currency: String,
        openingBalance: String,
    ): ApiResult<WalletResponse> =
        tokenStore.withToken { token ->
            api.create(
                token = token,
                trackerId = trackerId,
                name = name.trim(),
                currency = currency.trim().uppercase(),
                openingBalance = openingBalance.ifBlank { "0" },
            )
        }

    suspend fun rename(walletId: Int, name: String): ApiResult<WalletResponse> =
        tokenStore.withToken { token -> api.rename(token, walletId, name.trim()) }

    suspend fun delete(walletId: Int): ApiResult<Unit> =
        tokenStore.withToken { token -> api.delete(token, walletId) }
}