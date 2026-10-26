package com.abigailcandido.budgettracker.ui.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.TransactionRepository
import com.abigailcandido.budgettracker.data.WalletRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.TrackerSummaryResponse
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 */
private val OPENING_BALANCE_PATTERN = Regex("^-?\\d*(\\.\\d{0,2})?$")

/** The tabs under the wallets. Overview is the wallet table; the rest are entry lists. */
enum class EntryFilter(val label: String) {
    All("All"),
    Expenses("Expenses"),
    Income("Income"),
    Overview("Overview");

    fun matches(type: EntryType): Boolean = when (this) {
        All, Overview -> true
        Expenses -> type == EntryType.Expense
        Income -> type == EntryType.Income
    }
}

data class TrackerUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val range: DateRange = DateRange.month(LocalDate.now()),
    val wallets: List<WalletResponse> = emptyList(),
    val transactions: List<TransactionResponse> = emptyList(),
    val summary: TrackerSummaryResponse? = null,
    val selectedWalletId: Int? = null,
    val filter: EntryFilter = EntryFilter.All,
    val isCreateWalletOpen: Boolean = false,
    val newWalletName: String = "",
    val newWalletCurrency: String = DEFAULT_CURRENCY,
    val newOpeningBalance: String = "",
    val isCreatingWallet: Boolean = false,
    val createWalletError: String? = null,
    val pendingDeleteWalletId: Int? = null,
    val actionError: String? = null,
) {
    val selectedWallet: WalletResponse?
        get() = wallets.firstOrNull { it.id == selectedWalletId }

    /** Entries in the range, after the wallet and tab filters, still newest first. */
    val visibleTransactions: List<TransactionResponse>
        get() = transactions.filter { entry ->
            (selectedWalletId == null || entry.walletId == selectedWalletId) &&
                    filter.matches(entry.entryType)
        }

    /** Amounts only need a currency beside them once the tracker mixes currencies. */
    val showsCurrency: Boolean
        get() = wallets.map { it.currency }.distinct().size > 1

    val canCreateWallet: Boolean
        get() = !isCreatingWallet &&
                newWalletName.isNotBlank() &&
                newWalletCurrency.length == CURRENCY_LENGTH
}

class TrackerViewModel(
    private val trackerId: Int,
    private val walletRepository: WalletRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackerUiState())
    val uiState: StateFlow<TrackerUiState> = _uiState.asStateFlow()

    /**
     * The load in progress. Tapping the arrows quickly starts several loads;
     * only the newest is allowed to finish, so an older month's answer can
     * never arrive late and overwrite the month on screen.
     */
    private var loadJob: Job? = null

    init {
        refresh()
    }

    /**
     * Loads wallets, the range's entries and the range's overview side by
     * side, and shows them only once all three have arrived, so the cards,
     * the list and the table always agree with each other. After the first
     * load, a failed refresh keeps what is showing and reports the problem.
     */
    fun refresh() {
        loadJob?.cancel()
        val range = _uiState.value.range
        _uiState.update { it.copy(isRefreshing = !it.isLoading) }

        loadJob = viewModelScope.launch {
            val (walletResult, entryResult, summaryResult) = coroutineScope {
                val wallets = async { walletRepository.list(trackerId) }
                val entries = async {
                    transactionRepository.listForTracker(trackerId, range.start, range.end)
                }
                val summary = async {
                    transactionRepository.summary(trackerId, range.start, range.end)
                }
                Triple(wallets.await(), entries.await(), summary.await())
            }

            if (
                walletResult is ApiResult.Success &&
                entryResult is ApiResult.Success &&
                summaryResult is ApiResult.Success
            ) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = null,
                        wallets = walletResult.data,
                        transactions = entryResult.data,
                        summary = summaryResult.data,
                        // Forget a selected wallet that no longer exists.
                        selectedWalletId = current.selectedWalletId?.takeIf { id ->
                            walletResult.data.any { it.id == id }
                        },
                    )
                }
            } else {
                val message = listOf(walletResult, entryResult, summaryResult)
                    .filterIsInstance<ApiResult.Failure>()
                    .firstOrNull()
                    ?.message
                    ?: "Something went wrong"
                _uiState.update { current ->
                    if (current.isLoading) {
                        current.copy(isLoading = false, isRefreshing = false, errorMessage = message)
                    } else {
                        current.copy(isRefreshing = false, actionError = message)
                    }
                }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        refresh()
    }

    fun onRangeSelected(range: DateRange) {
        _uiState.update { it.copy(range = range) }
        refresh()
    }

    fun onPreviousRange() {
        onRangeSelected(_uiState.value.range.previous())
    }

    fun onNextRange() {
        onRangeSelected(_uiState.value.range.next())
    }

    /** Tapping a wallet narrows the list to it; tapping it again shows everything. */
    fun onWalletClick(walletId: Int) {
        _uiState.update {
            it.copy(selectedWalletId = if (it.selectedWalletId == walletId) null else walletId)
        }
    }

    fun onShowAllWallets() {
        _uiState.update { it.copy(selectedWalletId = null) }
    }

    fun onFilterSelected(filter: EntryFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun onAddWalletClick() {
        _uiState.update {
            it.copy(
                isCreateWalletOpen = true,
                newWalletName = "",
                newWalletCurrency = DEFAULT_CURRENCY,
                newOpeningBalance = "",
                createWalletError = null,
            )
        }
    }

    fun onDismissCreateWallet() {
        if (_uiState.value.isCreatingWallet) return
        _uiState.update { it.copy(isCreateWalletOpen = false, createWalletError = null) }
    }

    fun onWalletNameChange(value: String) {
        _uiState.update { it.copy(newWalletName = value, createWalletError = null) }
    }

    fun onWalletCurrencyChange(value: String) {
        if (!value.all { character -> character.isLetter() }) return
        _uiState.update {
            it.copy(
                newWalletCurrency = value.take(CURRENCY_LENGTH).uppercase(),
                createWalletError = null,
            )
        }
    }

    fun onOpeningBalanceChange(value: String) {
        if (!OPENING_BALANCE_PATTERN.matches(value)) return
        _uiState.update { it.copy(newOpeningBalance = value, createWalletError = null) }
    }

    fun createWallet() {
        val current = _uiState.value
        if (!current.canCreateWallet) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingWallet = true, createWalletError = null) }

            val result = walletRepository.create(
                trackerId = trackerId,
                name = current.newWalletName,
                currency = current.newWalletCurrency,
                openingBalance = current.newOpeningBalance,
            )

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isCreatingWallet = false, isCreateWalletOpen = false)
                    }
                    // Entries and overview too, since the opening balance is one of them.
                    refresh()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isCreatingWallet = false, createWalletError = result.message)
                }
            }
        }
    }

    fun onDeleteWalletClick(walletId: Int) {
        _uiState.update { it.copy(pendingDeleteWalletId = walletId) }
    }

    fun onDismissDeleteWallet() {
        _uiState.update { it.copy(pendingDeleteWalletId = null) }
    }

    fun confirmDeleteWallet() {
        val walletId = _uiState.value.pendingDeleteWalletId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteWalletId = null) }

            when (val result = walletRepository.delete(walletId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _uiState.update {
                    it.copy(actionError = result.message)
                }
            }
        }
    }

    fun onDismissActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    companion object {
        fun factory(
            trackerId: Int,
            walletRepository: WalletRepository,
            transactionRepository: TransactionRepository,
        ) = viewModelFactory {
            initializer { TrackerViewModel(trackerId, walletRepository, transactionRepository) }
        }
    }
}