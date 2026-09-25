package com.abigailcandido.budgettracker.ui.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.WalletRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_CURRENCY = "PHP"
private const val CURRENCY_LENGTH = 3

/**
 * Accepts an empty field, a bare number, or a number with up to two decimal
 * places, with an optional leading minus for wallets that start overdrawn.
 * Anything else is rejected keystroke by keystroke, so the field can never hold
 * a value the server would refuse.
 */
private val MONEY_PATTERN = Regex("^-?\\d*(\\.\\d{0,2})?$")

data class WalletListUiState(
    val isLoading: Boolean = true,
    val wallets: List<WalletResponse> = emptyList(),
    val errorMessage: String? = null,
    val isCreateDialogOpen: Boolean = false,
    val newName: String = "",
    val newCurrency: String = DEFAULT_CURRENCY,
    val newOpeningBalance: String = "",
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingDeleteId: Int? = null,
) {
    val canCreate: Boolean
        get() = !isCreating &&
                newName.isNotBlank() &&
                newCurrency.length == CURRENCY_LENGTH
}

class WalletListViewModel(
    private val trackerId: Int,
    private val repository: WalletRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletListUiState())
    val uiState: StateFlow<WalletListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = repository.list(trackerId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, wallets = result.data)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.message)
                }
            }
        }
    }

    fun onAddClick() {
        _uiState.update {
            it.copy(
                isCreateDialogOpen = true,
                newName = "",
                newCurrency = DEFAULT_CURRENCY,
                newOpeningBalance = "",
                createError = null,
            )
        }
    }

    fun onDismissCreate() {
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreateDialogOpen = false, createError = null) }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(newName = value, createError = null) }
    }

    fun onCurrencyChange(value: String) {
        if (!value.all { character -> character.isLetter() }) return
        _uiState.update {
            it.copy(
                newCurrency = value.take(CURRENCY_LENGTH).uppercase(),
                createError = null,
            )
        }
    }

    fun onOpeningBalanceChange(value: String) {
        if (!MONEY_PATTERN.matches(value)) return
        _uiState.update { it.copy(newOpeningBalance = value, createError = null) }
    }

    fun createWallet() {
        val current = _uiState.value
        if (!current.canCreate) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            val result = repository.create(
                trackerId = trackerId,
                name = current.newName,
                currency = current.newCurrency,
                openingBalance = current.newOpeningBalance,
            )

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isCreating = false, isCreateDialogOpen = false) }
                    refresh()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isCreating = false, createError = result.message)
                }
            }
        }
    }

    fun onDeleteClick(walletId: Int) {
        _uiState.update { it.copy(pendingDeleteId = walletId) }
    }

    fun onDismissDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete() {
        val walletId = _uiState.value.pendingDeleteId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteId = null) }

            when (val result = repository.delete(walletId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.message)
                }
            }
        }
    }

    companion object {
        fun factory(trackerId: Int, repository: WalletRepository) = viewModelFactory {
            initializer { WalletListViewModel(trackerId, repository) }
        }
    }
}