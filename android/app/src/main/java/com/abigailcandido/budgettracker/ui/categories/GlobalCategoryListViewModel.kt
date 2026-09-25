package com.abigailcandido.budgettracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import com.abigailcandido.budgettracker.data.remote.CategoryResponse
import com.abigailcandido.budgettracker.data.remote.TrackerResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GlobalCategoryListUiState(
    val isLoading: Boolean = true,
    val categories: List<CategoryResponse> = emptyList(),
    val errorMessage: String? = null,
    val addRequest: AddRequest? = null,
    val newName: String = "",
    val wantsSubcategories: Boolean = false,
    val subcategoryDraft: String = "",
    val subcategoryNames: List<String> = emptyList(),
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingDeleteId: Int? = null,
    val actionError: String? = null,
    val trackers: List<TrackerResponse> = emptyList(),
    val isLoadingTrackers: Boolean = false,
    val selectedTrackerIds: Set<Int>? = null,
    val expandedParentIds: Set<Int> = emptySet(),
) {
    val trimmedName: String
        get() = newName.trim()

    val parents: List<CategoryResponse>
        get() = categories.filter { it.parentId == null }

    val childrenByParent: Map<Int, List<CategoryResponse>>
        get() = categories
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }

    val blockingDuplicate: CategoryResponse?
        get() {
            if (trimmedName.isEmpty()) return null
            return when (val request = addRequest) {
                null -> null

                is AddRequest.TopLevel -> categories.firstOrNull {
                    it.parentId == null && it.name.equals(trimmedName, ignoreCase = true)
                }

                is AddRequest.Subcategory -> categories.firstOrNull {
                    it.parentId == request.parentId &&
                            it.name.equals(trimmedName, ignoreCase = true)
                }
            }
        }

    val canSubmit: Boolean
        get() = !isCreating &&
                trimmedName.isNotEmpty() &&
                blockingDuplicate == null &&
                !(addRequest is AddRequest.TopLevel && isLoadingTrackers)
}

class GlobalCategoryListViewModel(
    private val repository: CategoryRepository,
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlobalCategoryListUiState())
    val uiState: StateFlow<GlobalCategoryListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = repository.listAll()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = result.data.filter { item -> item.isGlobal },
                    )
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
            it.withFreshDraft(AddRequest.TopLevel).copy(
                isLoadingTrackers = true,
                trackers = emptyList(),
                selectedTrackerIds = null,
            )
        }

        viewModelScope.launch {
            when (val result = trackerRepository.list()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoadingTrackers = false,
                        trackers = result.data,
                        selectedTrackerIds = result.data.map { t -> t.id }.toSet(),
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoadingTrackers = false, createError = result.message)
                }
            }
        }
    }

    fun onAddSubcategoryClick(parent: CategoryResponse) {
        _uiState.update {
            it.withFreshDraft(
                AddRequest.Subcategory(
                    parentId = parent.id,
                    parentName = parent.name,
                    parentIsGlobal = true,
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

    fun onTrackerToggle(trackerId: Int) {
        _uiState.update { current ->
            val selected = current.selectedTrackerIds ?: return@update current
            current.copy(
                selectedTrackerIds = if (trackerId in selected) {
                    selected - trackerId
                } else {
                    selected + trackerId
                },
            )
        }
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

        val subcategories = subcategoriesToSend(
            current.wantsSubcategories,
            current.subcategoryNames,
            current.subcategoryDraft,
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            val result = if (request is AddRequest.Subcategory) {
                repository.createSubcategory(current.newName, request.parentId)
            } else {
                repository.createGlobal(
                    name = current.newName,
                    trackerIds = current.selectedTrackerIds?.toList(),
                    subcategoryNames = subcategories,
                )
            }

            when (result) {
                is ApiResult.Success -> {
                    val openId = when {
                        request is AddRequest.Subcategory -> request.parentId
                        subcategories.isNotEmpty() -> result.data.id
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            isCreating = false,
                            addRequest = null,
                            newName = "",
                            trackers = emptyList(),
                            selectedTrackerIds = null,
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

    private fun GlobalCategoryListUiState.withFreshDraft(request: AddRequest) = copy(
        addRequest = request,
        newName = "",
        wantsSubcategories = false,
        subcategoryDraft = "",
        subcategoryNames = emptyList(),
        createError = null,
    )

    companion object {
        fun factory(
            repository: CategoryRepository,
            trackerRepository: TrackerRepository,
        ) = viewModelFactory {
            initializer { GlobalCategoryListViewModel(repository, trackerRepository) }
        }
    }
}