package com.abigailcandido.budgettracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.CategoryKind
import com.abigailcandido.budgettracker.data.remote.categoryKind
import com.abigailcandido.budgettracker.data.remote.CategoryResponse
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
    val selectedKind: CategoryKind = CategoryKind.Expense,
    val errorMessage: String? = null,
    val addRequest: AddRequest? = null,
    val newName: String = "",
    val makeGlobal: Boolean = false,
    val wantsSubcategories: Boolean = false,
    val subcategoryDraft: String = "",
    val subcategoryNames: List<String> = emptyList(),
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingDeleteId: Int? = null,
    val busyCategoryIds: Set<Int> = emptySet(),
    val actionError: String? = null,
    val expandedParentIds: Set<Int> = emptySet(),
) {
    val trimmedName: String
        get() = newName.trim()

    /**
     * The categories on the tab currently showing. Everything below draws from
     * this, so an expense "Other" never blocks or matches an income "Other".
     */
    val visibleCategories: List<TrackerCategoryResponse>
        get() = categories.filter { it.categoryKind == selectedKind }

    val parents: List<TrackerCategoryResponse>
        get() = visibleCategories.filter { it.parentId == null }

    val childrenByParent: Map<Int, List<TrackerCategoryResponse>>
        get() = visibleCategories
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }

    /**
     * A shared category already carrying the name being typed. When set, the
     * page offers to switch that one on instead of creating a lookalike.
     */
    val existingGlobalMatch: TrackerCategoryResponse?
        get() {
            if (addRequest !is AddRequest.TopLevel || trimmedName.isEmpty()) return null
            return visibleCategories.firstOrNull {
                it.parentId == null &&
                        it.isGlobal &&
                        it.name.equals(trimmedName, ignoreCase = true)
            }
        }

    /**
     * A clash visible from this tracker, refused before asking the server. A
     * name private to a different tracker isn't in this list, so the server's
     * 409 remains the real backstop.
     */
    val blockingDuplicate: TrackerCategoryResponse?
        get() {
            if (trimmedName.isEmpty()) return null
            return when (val request = addRequest) {
                null -> null

                is AddRequest.TopLevel -> visibleCategories.firstOrNull {
                    it.parentId == null &&
                            !it.isGlobal &&
                            it.name.equals(trimmedName, ignoreCase = true)
                }

                is AddRequest.Subcategory -> visibleCategories.firstOrNull {
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

    fun onKindSelected(kind: CategoryKind) {
        _uiState.update { it.copy(selectedKind = kind) }
    }

    fun onAddTopLevelClick() {
        _uiState.update { it.withFreshDraft(AddRequest.TopLevel) }
    }

    fun onAddSubcategoryClick(parent: TrackerCategoryResponse) {
        _uiState.update {
            it.withFreshDraft(
                AddRequest.Subcategory(
                    parentId = parent.id,
                    parentName = parent.name,
                    parentIsGlobal = parent.isGlobal,
                )
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

    fun onMakeGlobalChange(value: Boolean) {
        _uiState.update { it.copy(makeGlobal = value) }
    }

    fun onWantsSubcategoriesChange(value: Boolean) {
        _uiState.update { it.copy(wantsSubcategories = value) }
    }

    fun onSubcategoryDraftChange(value: String) {
        _uiState.update { it.copy(subcategoryDraft = value) }
    }

    fun onAddSubcategoryName() {
        _uiState.update {
            it.copy(
                subcategoryNames = it.subcategoryNames.withSubcategory(it.subcategoryDraft),
                subcategoryDraft = "",
            )
        }
    }

    fun onRemoveSubcategoryName(name: String) {
        _uiState.update { it.copy(subcategoryNames = it.subcategoryNames - name) }
    }

    fun submitAdd() {
        val current = _uiState.value
        val request = current.addRequest ?: return
        if (!current.canSubmit) return

        val existing = current.existingGlobalMatch
        val subcategories = subcategoriesToSend(
            current.wantsSubcategories,
            current.subcategoryNames,
            current.subcategoryDraft,
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            val result = when {
                existing != null ->
                    repository.setEnabled(trackerId, existing.id, enabled = true)

                request is AddRequest.Subcategory ->
                    repository.createSubcategory(current.newName, request.parentId)

                // Shared, but switched on only here. Every other tracker sees
                // it switched off until someone decides it belongs there too.
                current.makeGlobal ->
                    repository.createGlobal(
                        name = current.newName,
                        trackerIds = listOf(trackerId),
                        subcategoryNames = subcategories,
                        kind = current.selectedKind,
                    )

                else ->
                    repository.createPrivate(
                        name = current.newName,
                        trackerId = trackerId,
                        subcategoryNames = subcategories,
                        kind = current.selectedKind,
                    )
            }

            when (result) {
                is ApiResult.Success -> {
                    val createdId = (result.data as? CategoryResponse)?.id
                    val openId = when {
                        request is AddRequest.Subcategory -> request.parentId
                        subcategories.isNotEmpty() -> createdId
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            isCreating = false,
                            addRequest = null,
                            newName = "",
                            expandedParentIds = if (openId != null) {
                                it.expandedParentIds + openId
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
                // A full reload: the server cascades switches, so the row that
                // was tapped is rarely the only one that moved.
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

    private fun CategoryListUiState.withFreshDraft(request: AddRequest) = copy(
        addRequest = request,
        newName = "",
        makeGlobal = false,
        wantsSubcategories = false,
        subcategoryDraft = "",
        subcategoryNames = emptyList(),
        createError = null,
    )

    companion object {
        fun factory(trackerId: Int, repository: CategoryRepository) = viewModelFactory {
            initializer { CategoryListViewModel(trackerId, repository) }
        }
    }
}