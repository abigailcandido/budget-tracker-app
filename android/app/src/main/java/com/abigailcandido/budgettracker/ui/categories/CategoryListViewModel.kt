package com.abigailcandido.budgettracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.TrackerCategoryResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the add dialog is currently being used for.
 *
 * Held as data rather than as a boolean plus a nullable parent id that only
 * means anything when the boolean is set. The compiler then guarantees a
 * subcategory always has a parent, instead of that being a rule to remember.
 */
sealed interface AddRequest {
    data object TopLevel : AddRequest

    data class Subcategory(
        val parentId: Int,
        val parentName: String,
        val parentIsGlobal: Boolean,
    ) : AddRequest
}

data class CategoryListUiState(
    val isLoading: Boolean = true,
    val categories: List<TrackerCategoryResponse> = emptyList(),
    val errorMessage: String? = null,
    val addRequest: AddRequest? = null,
    val newName: String = "",
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingDeleteId: Int? = null,
    val busyCategoryIds: Set<Int> = emptySet(),
    val actionError: String? = null,
    // Which parents are showing their subcategories. Groups start closed so a
    // long list stays scannable; the point of the hierarchy is that you only
    // open the family you're actually working on.
    val expandedParentIds: Set<Int> = emptySet(),
) {
    val trimmedName: String
        get() = newName.trim()

    /** Top-level categories, already in name order from the server. */
    val parents: List<TrackerCategoryResponse>
        get() = categories.filter { it.parentId == null }

    /** Subcategories, grouped under the id of the parent they belong to. */
    val childrenByParent: Map<Int, List<TrackerCategoryResponse>>
        get() = categories
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }

    /**
     * A shared category already carrying the name being typed.
     *
     * When this is non-null the dialog stops offering to create anything and
     * offers to switch that one on instead. A second category with the same
     * name would leave two entries nobody can tell apart in every picker from
     * then on — and the one they meant already exists.
     */
    val existingGlobalMatch: TrackerCategoryResponse?
        get() {
            if (addRequest !is AddRequest.TopLevel || trimmedName.isEmpty()) return null
            return categories.firstOrNull {
                it.parentId == null &&
                        it.isGlobal &&
                        it.name.equals(trimmedName, ignoreCase = true)
            }
        }

    /**
     * A name clash the app can see and should refuse without asking the server.
     *
     * Only clashes visible from this tracker are caught here. A name belonging
     * to a category private to a different tracker isn't in this list at all,
     * so the server's 409 stays the real backstop.
     */
    val blockingDuplicate: TrackerCategoryResponse?
        get() {
            if (trimmedName.isEmpty()) return null
            return when (val request = addRequest) {
                null -> null

                is AddRequest.TopLevel -> categories.firstOrNull {
                    it.parentId == null &&
                            !it.isGlobal &&
                            it.name.equals(trimmedName, ignoreCase = true)
                }

                is AddRequest.Subcategory -> categories.firstOrNull {
                    it.parentId == request.parentId &&
                            it.name.equals(trimmedName, ignoreCase = true)
                }
            }
        }

    val canSubmit: Boolean
        get() = !isCreating && trimmedName.isNotEmpty() && blockingDuplicate == null
}

class CategoryListViewModel(
    private val trackerId: Int,
    private val repository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryListUiState())
    val uiState: StateFlow<CategoryListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = repository.listForTracker(trackerId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, categories = result.data)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.message)
                }
            }
        }
    }

    fun onToggleExpanded(parentId: Int) {
        _uiState.update { current ->
            current.copy(
                expandedParentIds = if (parentId in current.expandedParentIds) {
                    current.expandedParentIds - parentId
                } else {
                    current.expandedParentIds + parentId
                },
            )
        }
    }

    fun onAddTopLevelClick() {
        _uiState.update {
            it.copy(addRequest = AddRequest.TopLevel, newName = "", createError = null)
        }
    }

    fun onAddSubcategoryClick(parent: TrackerCategoryResponse) {
        _uiState.update {
            it.copy(
                addRequest = AddRequest.Subcategory(
                    parentId = parent.id,
                    parentName = parent.name,
                    parentIsGlobal = parent.isGlobal,
                ),
                newName = "",
                createError = null,
            )
        }
    }

    fun onDismissAdd() {
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(addRequest = null, createError = null) }
    }

    fun onNewNameChange(value: String) {
        _uiState.update { it.copy(newName = value, createError = null) }
    }

    fun submitAdd() {
        val current = _uiState.value
        val request = current.addRequest ?: return
        if (!current.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            val existing = current.existingGlobalMatch

            val result = when {
                existing != null ->
                    repository.setEnabled(trackerId, existing.id, enabled = true)

                request is AddRequest.Subcategory ->
                    repository.createSubcategory(current.newName, request.parentId)

                else ->
                    repository.createPrivate(current.newName, trackerId)
            }

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isCreating = false,
                            addRequest = null,
                            newName = "",
                            // Open the parent, so a new subcategory appears in
                            // front of the user rather than inside a closed
                            // group they then have to go looking for.
                            expandedParentIds = if (request is AddRequest.Subcategory) {
                                it.expandedParentIds + request.parentId
                            } else {
                                it.expandedParentIds
                            },
                        )
                    }
                    refresh()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isCreating = false, createError = result.message)
                }
            }
        }
    }

    fun onEnabledChange(categoryId: Int, enabled: Boolean) {
        if (categoryId in _uiState.value.busyCategoryIds) return

        viewModelScope.launch {
            _uiState.update { it.copy(busyCategoryIds = it.busyCategoryIds + categoryId) }

            val result = repository.setEnabled(trackerId, categoryId, enabled)

            _uiState.update { it.copy(busyCategoryIds = it.busyCategoryIds - categoryId) }

            when (result) {
                // A full reload rather than flipping one switch locally. The
                // server cascades these — turning a parent off takes its
                // children, turning the last child off takes the parent — so
                // the row that was tapped is rarely the only row that moved,
                // and guessing which others did is how a screen starts lying.
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _uiState.update {
                    it.copy(actionError = result.message)
                }
            }
        }
    }

    fun onDeleteClick(categoryId: Int) {
        _uiState.update { it.copy(pendingDeleteId = categoryId) }
    }

    fun onDismissDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete() {
        val categoryId = _uiState.value.pendingDeleteId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteId = null) }

            when (val result = repository.delete(categoryId)) {
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
        fun factory(trackerId: Int, repository: CategoryRepository) = viewModelFactory {
            initializer { CategoryListViewModel(trackerId, repository) }
        }
    }
}