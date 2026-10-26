package com.abigailcandido.budgettracker.ui.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.CategoryRepository
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

/** The tabs under the wallets, in screen order. Overview is the wallet table; the rest are entry lists. */
enum class EntryFilter(val label: String) {
    Overview("Overview"),
    All("All Transactions"),
    Income("Income"),
    Expenses("Expenses");

    fun matches(type: EntryType): Boolean = when (this) {
        Overview, All -> true
        Income -> type == EntryType.Income
        Expenses -> type == EntryType.Expense
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
    val filter: EntryFilter = EntryFilter.Overview,
    val isCreateWalletOpen: Boolean = false,
    val newWalletName: String = "",
    val newWalletCurrency: String = DEFAULT_CURRENCY,
    val newOpeningBalance: String = "",
    val isCreatingWallet: Boolean = false,
    val createWalletError: String? = null,
    val pendingDeleteWalletId: Int? = null,
    val actionError: String? = null,
    /** The add-or-edit page's form while that page is open, otherwise null. */
    val entryForm: EntryForm? = null,
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
    private val categoryRepository: CategoryRepository,
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

    /** The entry form's category load, cancelled if the form is closed or reopened. */
    private var categoryJob: Job? = null

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

    // ---- The add-or-edit transaction page ----

    /**
     * Opens a blank form. It starts as an income on the Income tab and an
     * expense everywhere else, in the selected wallet or else the first one,
     * dated today, or the last day of the period showing if that is over.
     */
    fun onAddEntryClick() {
        val current = _uiState.value
        val wallet = current.selectedWallet ?: current.wallets.firstOrNull() ?: return
        val today = LocalDate.now()

        _uiState.update {
            it.copy(
                entryForm = EntryForm(
                    type = if (current.filter == EntryFilter.Income) {
                        EntryType.Income
                    } else {
                        EntryType.Expense
                    },
                    walletId = wallet.id,
                    date = if (current.range.end.isBefore(today)) current.range.end else today,
                ),
            )
        }
        loadEntryCategories(keepId = null)
    }

    /** Opens the form filled in with an existing entry, ready to correct. */
    fun onEntryClick(entry: TransactionResponse) {
        val type = entry.entryType

        _uiState.update {
            it.copy(
                entryForm = EntryForm(
                    editingId = entry.id,
                    type = type,
                    walletId = entry.walletId,
                    currency = entry.currency,
                    categoryId = entry.categoryId,
                    originalCategoryId = entry.categoryId,
                    amount = entry.amount,
                    date = runCatching { LocalDate.parse(entry.transactionDate) }
                        .getOrDefault(LocalDate.now()),
                    note = entry.description.orEmpty(),
                ),
            )
        }

        // An opening balance has no category, so there is nothing to load.
        if (type != EntryType.Opening) loadEntryCategories(keepId = entry.categoryId)
    }

    fun retryEntryCategories() {
        loadEntryCategories(keepId = _uiState.value.entryForm?.originalCategoryId)
    }

    fun onEntryTypeChange(type: EntryType) {
        updateForm { form ->
            if (form.isOpening || type == EntryType.Opening) {
                form
            } else {
                form.copy(
                    type = type,
                    // An expense category can't come along to an income, or the
                    // other way round, so the choice is cleared when it doesn't fit.
                    categoryId = form.categoryId?.takeIf { id ->
                        form.categoriesFor(type).any { it.id == id }
                    },
                    error = null,
                )
            }
        }
    }

    fun onEntryWalletChange(walletId: Int) {
        updateForm { form ->
            if (form.isOpening) form else form.copy(walletId = walletId, error = null)
        }
    }

    fun onEntryCategoryChange(categoryId: Int) {
        updateForm { it.copy(categoryId = categoryId, error = null) }
    }

    fun onEntryAmountChange(value: String) {
        updateForm { form ->
            if (form.acceptsAmount(value)) form.copy(amount = value, error = null) else form
        }
    }

    fun onEntryDateChange(date: LocalDate) {
        // The date picker already blocks future days; this is a second lock.
        if (date.isAfter(LocalDate.now())) return
        updateForm { it.copy(date = date, error = null) }
    }

    fun onEntryNoteChange(value: String) {
        updateForm { it.copy(note = value.take(NOTE_MAX_LENGTH), error = null) }
    }

    fun saveEntry() {
        val form = _uiState.value.entryForm ?: return
        val walletId = form.walletId ?: return
        if (!form.canSave) return

        viewModelScope.launch {
            updateForm { it.copy(isSaving = true, error = null) }

            val editingId = form.editingId
            val categoryId = form.categoryId
            val date = form.date.toString()

            val result = when {
                // An opening balance keeps its wallet and has no type or category,
                // so those are left out of the correction altogether.
                editingId != null && form.isOpening -> transactionRepository.update(
                    transactionId = editingId,
                    walletId = null,
                    type = null,
                    categoryId = null,
                    amount = form.amount,
                    date = date,
                    description = form.note,
                )

                editingId != null -> transactionRepository.update(
                    transactionId = editingId,
                    walletId = walletId,
                    type = form.type,
                    categoryId = categoryId,
                    amount = form.amount,
                    date = date,
                    description = form.note,
                )

                categoryId != null -> transactionRepository.create(
                    walletId = walletId,
                    type = form.type,
                    amount = form.amount,
                    categoryId = categoryId,
                    date = date,
                    description = form.note,
                )

                // canSave already guarantees a category, so this never happens.
                else -> ApiResult.Failure("Choose a category")
            }

            when (result) {
                is ApiResult.Success -> closeEntryFormAndRefresh()
                is ApiResult.Failure -> updateForm {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun onDeleteEntryClick() {
        updateForm { it.copy(isConfirmingDelete = true) }
    }

    fun onDismissDeleteEntry() {
        updateForm { it.copy(isConfirmingDelete = false) }
    }

    fun confirmDeleteEntry() {
        val editingId = _uiState.value.entryForm?.editingId ?: return

        viewModelScope.launch {
            updateForm { it.copy(isConfirmingDelete = false, isDeleting = true, error = null) }

            when (val result = transactionRepository.delete(editingId)) {
                is ApiResult.Success -> closeEntryFormAndRefresh()
                is ApiResult.Failure -> updateForm {
                    it.copy(isDeleting = false, error = result.message)
                }
            }
        }
    }

    /** Cancel, or the back gesture. Ignored while a save or delete is on its way. */
    fun onDismissEntryForm() {
        if (_uiState.value.entryForm?.isBusy == true) return
        categoryJob?.cancel()
        _uiState.update { it.copy(entryForm = null) }
    }

    /**
     * Loads every category the tracker can see, switched on or not, and keeps
     * the ones an entry can use. They are fetched each time the page opens,
     * so a category added a moment ago on the Categories screen is there.
     */
    private fun loadEntryCategories(keepId: Int?) {
        categoryJob?.cancel()
        updateForm { it.copy(isLoadingCategories = true, categoryLoadError = null) }

        categoryJob = viewModelScope.launch {
            when (val result = categoryRepository.listForTracker(trackerId)) {
                is ApiResult.Success -> updateForm {
                    it.copy(
                        isLoadingCategories = false,
                        categories = categoryOptions(result.data, keepId),
                    )
                }
                is ApiResult.Failure -> updateForm {
                    it.copy(isLoadingCategories = false, categoryLoadError = result.message)
                }
            }
        }
    }

    private fun closeEntryFormAndRefresh() {
        _uiState.update { it.copy(entryForm = null) }
        // Balances, lists and the overview all change with an entry.
        refresh()
    }

    /** Changes the form if it is open, and does nothing if it has been closed. */
    private fun updateForm(transform: (EntryForm) -> EntryForm) {
        _uiState.update { state ->
            val form = state.entryForm ?: return@update state
            state.copy(entryForm = transform(form))
        }
    }

    companion object {
        fun factory(
            trackerId: Int,
            walletRepository: WalletRepository,
            categoryRepository: CategoryRepository,
            transactionRepository: TransactionRepository,
        ) = viewModelFactory {
            initializer {
                TrackerViewModel(
                    trackerId,
                    walletRepository,
                    categoryRepository,
                    transactionRepository,
                )
            }
        }
    }
}