package com.abigailcandido.budgettracker.ui.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.TransactionRepository
import com.abigailcandido.budgettracker.data.WalletRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.CategoryKind
import com.abigailcandido.budgettracker.data.remote.EntryType
import com.abigailcandido.budgettracker.data.remote.PromoteOtherResponse
import com.abigailcandido.budgettracker.data.remote.TrackerSummaryResponse
import com.abigailcandido.budgettracker.data.remote.TransactionResponse
import com.abigailcandido.budgettracker.data.remote.WalletResponse
import com.abigailcandido.budgettracker.data.remote.entryType
import com.abigailcandido.budgettracker.ui.pin.PIN_LENGTH
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
    /** A short message after something worked, such as a new category from Others. */
    val notice: String? = null,
    /** The details page's entry while that page is open, otherwise null. */
    val entryDetail: EntryDetailState? = null,
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
    private val authRepository: AuthRepository,
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

    /** The details page's history load, cancelled if the page is closed or reopened. */
    private var historyJob: Job? = null

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

    fun onDismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    // ---- The details page, and the PIN check in front of Edit ----

    /**
     * Opens an entry's details. Only a corrected or deleted entry has a
     * history worth fetching; any other entry's history is just itself.
     */
    fun onEntryClick(entry: TransactionResponse) {
        historyJob?.cancel()
        val detail = EntryDetailState(entry = entry)
        _uiState.update { it.copy(entryDetail = detail) }
        if (detail.hasHistory) loadEntryHistory()
    }

    fun retryEntryHistory() {
        loadEntryHistory()
    }

    /** Back from the details page. Ignored while anything on it is on its way to the server. */
    fun onDismissEntryDetail() {
        if (_uiState.value.entryDetail?.isBusy == true) return
        historyJob?.cancel()
        _uiState.update { it.copy(entryDetail = null) }
    }

    /** Edit asks for the PIN every time, before the form opens. A deleted entry has no Edit. */
    fun onEditEntryClick() {
        updateDetail { detail ->
            if (detail.entry.isDeleted) {
                detail
            } else {
                detail.copy(isPinOpen = true, pin = "", pinError = null)
            }
        }
    }

    fun onEntryPinChange(value: String) {
        if (!value.all(Char::isDigit)) return
        updateDetail { it.copy(pin = value.take(PIN_LENGTH), pinError = null) }
    }

    fun onDismissEntryPin() {
        updateDetail { detail ->
            if (detail.isCheckingPin) detail else detail.copy(isPinOpen = false, pin = "", pinError = null)
        }
    }

    fun confirmEntryPin() {
        val detail = _uiState.value.entryDetail ?: return
        if (!detail.canCheckPin) return

        viewModelScope.launch {
            updateDetail { it.copy(isCheckingPin = true, pinError = null) }

            when (val result = authRepository.verifyPin(detail.pin)) {
                is ApiResult.Success -> {
                    updateDetail { it.copy(isCheckingPin = false, isPinOpen = false, pin = "") }
                    // The details page opens the form page as soon as this is set.
                    openFormFor(detail.entry)
                }
                is ApiResult.Failure -> updateDetail {
                    it.copy(isCheckingPin = false, pin = "", pinError = result.message)
                }
            }
        }
    }

    // ---- Deleting, with a reason and a code sent to the account's email ----

    fun onDeleteEntryClick() {
        updateDetail { detail ->
            if (detail.entry.isDeleted) {
                detail
            } else {
                detail.copy(
                    isDeleteOpen = true,
                    deleteReason = "",
                    codeSentTo = null,
                    deleteCode = "",
                    deleteError = null,
                )
            }
        }
    }

    fun onDeleteReasonChange(value: String) {
        updateDetail { it.copy(deleteReason = value.take(NOTE_MAX_LENGTH), deleteError = null) }
    }

    fun onDeleteCodeChange(value: String) {
        if (!value.all(Char::isDigit)) return
        updateDetail { it.copy(deleteCode = value.take(EMAIL_CODE_LENGTH), deleteError = null) }
    }

    fun onDismissDelete() {
        updateDetail { detail ->
            if (detail.isSendingCode || detail.isDeleting) {
                detail
            } else {
                detail.copy(isDeleteOpen = false, deleteCode = "", deleteError = null)
            }
        }
    }

    /**
     * Asks the server to email a code for deleting this entry. Asking again
     * sends a new code; the server cancels the old one, and refuses for a
     * moment after a code was sent, so a double tap never sends two.
     */
    fun sendDeleteCode() {
        val detail = _uiState.value.entryDetail ?: return
        if (!detail.canSendCode) return

        viewModelScope.launch {
            updateDetail { it.copy(isSendingCode = true, deleteError = null) }

            when (val result = transactionRepository.sendDeleteCode(detail.entry.id)) {
                is ApiResult.Success -> updateDetail {
                    it.copy(
                        isSendingCode = false,
                        codeSentTo = result.data.sentTo,
                        codeMinutes = result.data.expiresInMinutes,
                        deleteCode = "",
                    )
                }
                is ApiResult.Failure -> updateDetail {
                    it.copy(isSendingCode = false, deleteError = result.message)
                }
            }
        }
    }

    /**
     * The server checks the code. A wrong one keeps the dialog open with the
     * reason still filled in, and the message says how many tries are left.
     */
    fun confirmDeleteEntry() {
        val detail = _uiState.value.entryDetail ?: return
        if (!detail.canConfirmDelete) return

        viewModelScope.launch {
            updateDetail { it.copy(isDeleting = true, deleteError = null) }

            when (
                val result = transactionRepository.delete(
                    detail.entry.id,
                    detail.deleteReason,
                    detail.deleteCode,
                )
            ) {
                is ApiResult.Success -> closeDetailAndRefresh(notice = null)
                is ApiResult.Failure -> updateDetail {
                    it.copy(isDeleting = false, deleteCode = "", deleteError = result.message)
                }
            }
        }
    }

    // ---- Restoring a deleted entry ----

    fun onRestoreEntryClick() {
        updateDetail { detail ->
            if (!detail.entry.isDeleted) {
                detail
            } else {
                detail.copy(isRestoreOpen = true, restoreReason = "", restoreError = null)
            }
        }
    }

    fun onRestoreReasonChange(value: String) {
        updateDetail { it.copy(restoreReason = value.take(NOTE_MAX_LENGTH), restoreError = null) }
    }

    fun onDismissRestore() {
        updateDetail { detail ->
            if (detail.isRestoring) detail else detail.copy(isRestoreOpen = false, restoreError = null)
        }
    }

    /** The restored entry is a new version, so the page closes and the list shows it. */
    fun confirmRestore() {
        val detail = _uiState.value.entryDetail ?: return
        if (detail.isRestoring) return

        viewModelScope.launch {
            updateDetail { it.copy(isRestoring = true, restoreError = null) }

            when (val result = transactionRepository.restore(detail.entry.id, detail.restoreReason)) {
                is ApiResult.Success -> closeDetailAndRefresh(
                    notice = "Restored. It counts towards ${result.data.walletName}'s balance again.",
                )
                is ApiResult.Failure -> updateDetail {
                    it.copy(isRestoring = false, restoreError = result.message)
                }
            }
        }
    }

    // ---- Giving an Others entry a category of its own ----

    /** Opens the dialog with the entry's own Others name filled in. */
    fun onPromoteEntryClick() {
        updateDetail { detail ->
            if (!detail.canBePromoted) {
                detail
            } else {
                detail.copy(
                    isPromoteOpen = true,
                    promoteName = detail.entry.otherName.orEmpty(),
                    promoteWithSubcategory = false,
                    promoteSubcategoryName = "",
                    promoteError = null,
                )
            }
        }
    }

    fun onPromoteNameChange(value: String) {
        updateDetail { it.copy(promoteName = value.take(OTHER_NAME_MAX_LENGTH), promoteError = null) }
    }

    fun onPromoteWithSubcategoryChange(value: Boolean) {
        updateDetail { detail ->
            if (!detail.canPromoteWithSubcategory) {
                detail
            } else {
                detail.copy(promoteWithSubcategory = value, promoteError = null)
            }
        }
    }

    fun onPromoteSubcategoryNameChange(value: String) {
        updateDetail {
            it.copy(promoteSubcategoryName = value.take(OTHER_NAME_MAX_LENGTH), promoteError = null)
        }
    }

    fun onDismissPromote() {
        updateDetail { detail ->
            if (detail.isPromoting) detail else detail.copy(isPromoteOpen = false, promoteError = null)
        }
    }

    /**
     * The server finds the category with that name, ignoring capitals, or
     * makes it, and moves every Others entry with this name into it. Each
     * move is a correction, so the entry on this page is replaced: the page
     * closes, and a notice on the tracker screen says what happened.
     */
    fun confirmPromote() {
        val detail = _uiState.value.entryDetail ?: return
        if (!detail.canConfirmPromote) return
        val subcategoryName = detail.promoteSubcategoryName
            .takeIf { detail.canPromoteWithSubcategory && detail.promoteWithSubcategory }

        viewModelScope.launch {
            updateDetail { it.copy(isPromoting = true, promoteError = null) }

            when (
                val result = transactionRepository.promoteOther(
                    detail.entry.id,
                    detail.promoteName,
                    subcategoryName,
                )
            ) {
                is ApiResult.Success -> closeDetailAndRefresh(notice = promoteNotice(result.data))
                is ApiResult.Failure -> updateDetail {
                    it.copy(isPromoting = false, promoteError = result.message)
                }
            }
        }
    }

    private fun promoteNotice(result: PromoteOtherResponse): String {
        val entries = if (result.moved == 1) "1 entry" else "${result.moved} entries"
        return if (result.created) {
            "${result.categoryName} is now one of your categories. $entries moved into it from Others."
        } else {
            "${result.categoryName} was already one of your categories, so $entries moved into it from Others."
        }
    }

    private fun loadEntryHistory() {
        val entryId = _uiState.value.entryDetail?.entry?.id ?: return
        historyJob?.cancel()
        updateDetail { it.copy(isLoadingHistory = true, historyError = null) }

        historyJob = viewModelScope.launch {
            when (val result = transactionRepository.history(entryId)) {
                is ApiResult.Success -> updateDetail {
                    it.copy(isLoadingHistory = false, history = result.data)
                }
                is ApiResult.Failure -> updateDetail {
                    it.copy(isLoadingHistory = false, historyError = result.message)
                }
            }
        }
    }

    /**
     * After a deletion, a restore or a move out of Others, the version the page
     * showed is no longer the one in the list, so the page closes and the
     * list is refreshed, with a short notice if there is something to say.
     */
    private fun closeDetailAndRefresh(notice: String?) {
        historyJob?.cancel()
        _uiState.update { it.copy(entryDetail = null, notice = notice) }
        refresh()
    }

    /** Changes the details page's state if it is open, and does nothing otherwise. */
    private fun updateDetail(transform: (EntryDetailState) -> EntryDetailState) {
        _uiState.update { state ->
            val detail = state.entryDetail ?: return@update state
            state.copy(entryDetail = transform(detail))
        }
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

    /**
     * Opens the form filled in with an existing entry, ready to correct. The
     * Category and Sub-category fields are filled in once the categories load.
     */
    private fun openFormFor(entry: TransactionResponse) {
        val type = entry.entryType

        _uiState.update {
            it.copy(
                entryForm = EntryForm(
                    editingId = entry.id,
                    type = type,
                    walletId = entry.walletId,
                    currency = entry.currency,
                    originalCategoryId = entry.categoryId,
                    otherName = entry.otherName.orEmpty(),
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

    /** Expense or Income. Only a new entry has the switch; a correction keeps its type. */
    fun onEntryTypeChange(type: EntryType) {
        updateForm { form ->
            if (!form.isNew || form.isOpening || type == EntryType.Opening) {
                form
            } else {
                // An expense category can't come along to an income, or the
                // other way round, so the choice is cleared when it doesn't fit.
                // Others is offered for both, so it stays.
                val keepsCategory = form.topCategoryId == OTHERS_CHOICE ||
                        form.categoriesFor(type).any { it.id == form.topCategoryId }
                form.copy(
                    type = type,
                    topCategoryId = form.topCategoryId.takeIf { keepsCategory },
                    subcategoryId = form.subcategoryId.takeIf { keepsCategory },
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

    /**
     * A top-level category, or Others, from the Category field. Choosing a
     * different one empties the Sub-category field, since its choices change.
     */
    fun onEntryCategoryChange(topCategoryId: Int) {
        updateForm { form ->
            val isKnown = topCategoryId == OTHERS_CHOICE ||
                    form.availableCategories.any { it.id == topCategoryId }
            if (!isKnown || topCategoryId == form.topCategoryId) {
                form
            } else {
                form.copy(topCategoryId = topCategoryId, subcategoryId = null, error = null)
            }
        }
    }

    /** A subcategory, or Others, from the Sub-category field. */
    fun onEntrySubcategoryChange(subcategoryId: Int) {
        updateForm { it.copy(subcategoryId = subcategoryId, error = null) }
    }

    /** What an entry under Others was. It is kept if the user switches away and back. */
    fun onEntryOtherNameChange(value: String) {
        updateForm { it.copy(otherName = value.take(OTHER_NAME_MAX_LENGTH), error = null) }
    }

    /** The "Add to my categories" switch under Others. Off by default. */
    fun onAddToCategoriesChange(value: Boolean) {
        updateForm { it.copy(addToCategories = value, error = null) }
    }

    /** Whether the new top-level category holds a sub-category too. */
    fun onWithSubcategoryChange(value: Boolean) {
        updateForm { form ->
            if (!form.canHaveSubcategory) form else form.copy(withSubcategory = value, error = null)
        }
    }

    fun onSubcategoryNameChange(value: String) {
        updateForm { it.copy(subcategoryName = value.take(OTHER_NAME_MAX_LENGTH), error = null) }
    }

    /**
     * The name typed under Others is already a category, so the entry is
     * filed there instead, and the name is no longer needed.
     */
    fun onUseCategoryMatch() {
        updateForm { form ->
            val match = form.otherNameMatch ?: return@updateForm form
            form.copy(
                topCategoryId = match.topCategoryId,
                subcategoryId = match.subcategoryId,
                otherName = "",
                error = null,
            )
        }
    }

    /** Why the entry is being corrected. Optional. */
    fun onEntryReasonChange(value: String) {
        updateForm { it.copy(reason = value.take(NOTE_MAX_LENGTH), error = null) }
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
            val date = form.date.toString()

            val kind = if (form.type == EntryType.Income) CategoryKind.Income else CategoryKind.Expense

            // Under Others, the category is fetched first: the new one named in
            // the form when "Add to my categories" is on, otherwise the Others
            // category itself. Either is made on the server if it does not exist yet.
            val categoryId: Int? = when {
                form.isOpening -> null
                form.isMakingCategory -> {
                    val made = categoryRepository.findOrCreate(
                        trackerId = trackerId,
                        kind = kind,
                        parentId = form.othersParentId,
                        name = form.otherName,
                        subcategoryName = form.subcategoryName
                            .takeIf { form.canHaveSubcategory && form.withSubcategory },
                    )
                    when (made) {
                        is ApiResult.Success -> made.data.id
                        is ApiResult.Failure -> {
                            updateForm { it.copy(isSaving = false, error = made.message) }
                            return@launch
                        }
                    }
                }
                form.isOthersChosen -> {
                    when (val others = categoryRepository.othersCategory(trackerId, kind, form.othersParentId)) {
                        is ApiResult.Success -> others.data.id
                        is ApiResult.Failure -> {
                            updateForm { it.copy(isSaving = false, error = others.message) }
                            return@launch
                        }
                    }
                }
                else -> form.leafCategoryId
            }
            // Only an entry left under Others keeps a name of its own.
            val otherName = if (form.isOthersChosen && !form.isMakingCategory) {
                form.otherName.trim()
            } else {
                null
            }

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
                    reason = form.reason,
                )

                editingId != null -> transactionRepository.update(
                    transactionId = editingId,
                    walletId = walletId,
                    type = form.type,
                    categoryId = categoryId,
                    amount = form.amount,
                    date = date,
                    description = form.note,
                    otherName = otherName,
                    reason = form.reason,
                )

                categoryId != null -> transactionRepository.create(
                    walletId = walletId,
                    type = form.type,
                    amount = form.amount,
                    categoryId = categoryId,
                    date = date,
                    description = form.note,
                    otherName = otherName,
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
                is ApiResult.Success -> updateForm { form ->
                    // When correcting, the two fields start on the entry's own
                    // category: itself, its parent and itself, or Others.
                    val (topId, subId) = if (form.topCategoryId == null && keepId != null) {
                        choiceFor(keepId, result.data)
                    } else {
                        form.topCategoryId to form.subcategoryId
                    }
                    form.copy(
                        isLoadingCategories = false,
                        categories = categoryOptions(result.data, keepId),
                        topCategoryId = topId,
                        subcategoryId = subId,
                    )
                }
                is ApiResult.Failure -> updateForm {
                    it.copy(isLoadingCategories = false, categoryLoadError = result.message)
                }
            }
        }
    }

    private fun closeEntryFormAndRefresh() {
        // The details page closes too: the version it showed has just been
        // replaced or deleted, so the list is where the user goes back to.
        historyJob?.cancel()
        _uiState.update { it.copy(entryForm = null, entryDetail = null) }
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
            authRepository: AuthRepository,
        ) = viewModelFactory {
            initializer {
                TrackerViewModel(
                    trackerId,
                    walletRepository,
                    categoryRepository,
                    transactionRepository,
                    authRepository,
                )
            }
        }
    }
}