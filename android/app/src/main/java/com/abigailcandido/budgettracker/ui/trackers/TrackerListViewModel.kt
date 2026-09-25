package com.abigailcandido.budgettracker.ui.trackers

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

data class TrackerListUiState(
    val isLoading: Boolean = true,
    val trackers: List<TrackerResponse> = emptyList(),
    val errorMessage: String? = null,
    val isCreateDialogOpen: Boolean = false,
    val newName: String = "",
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingDeleteId: Int? = null,
    val availableCategories: List<CategoryResponse> = emptyList(),
    val isLoadingCategories: Boolean = false,
    // Null means "the user never got to choose", which happens when the
    // category list failed to load. That travels all the way to the server as
    // an omitted field, so it falls back to switching all of them on. An empty
    // set is different: it means they looked at the list and unticked
    // everything, and that choice is respected.
    val selectedCategoryIds: Set<Int>? = null,
) {
    val canCreate: Boolean
        get() = !isCreating && !isLoadingCategories && newName.isNotBlank()
}

class TrackerListViewModel(
    private val repository: TrackerRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackerListUiState())
    val uiState: StateFlow<TrackerListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = repository.list()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, trackers = result.data)
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
                createError = null,
                isLoadingCategories = true,
                availableCategories = emptyList(),
                selectedCategoryIds = null,
            )
        }

        viewModelScope.launch {
            when (val result = categoryRepository.listAll()) {
                is ApiResult.Success -> {
                    // Only global categories are offered. A private one already
                    // belongs to a different tracker, and the server would
                    // refuse it — so it should never appear as a tickable box.
                    val globals = result.data.filter { it.isGlobal }
                    _uiState.update {
                        it.copy(
                            isLoadingCategories = false,
                            availableCategories = globals,
                            // Everything ticked to start with: wanting them all
                            // is the common case, and unticking two is less
                            // work than ticking eight.
                            selectedCategoryIds = globals.map { item -> item.id }.toSet(),
                        )
                    }
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoadingCategories = false, createError = result.message)
                }
            }
        }
    }

    fun onDismissCreate() {
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreateDialogOpen = false, createError = null) }
    }

    fun onNewNameChange(value: String) {
        _uiState.update { it.copy(newName = value, createError = null) }
    }

    fun onCategoryToggle(categoryId: Int) {
        _uiState.update { current ->
            val selected = current.selectedCategoryIds ?: return@update current
            current.copy(
                selectedCategoryIds = if (categoryId in selected) {
                    selected - categoryId
                } else {
                    selected + categoryId
                },
            )
        }
    }

    fun createTracker() {
        val current = _uiState.value
        if (!current.canCreate) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            val result = repository.create(
                name = current.newName,
                categoryIds = current.selectedCategoryIds?.toList(),
            )

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isCreating = false,
                            isCreateDialogOpen = false,
                            newName = "",
                            availableCategories = emptyList(),
                            selectedCategoryIds = null,
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

    fun onDeleteClick(trackerId: Int) {
        _uiState.update { it.copy(pendingDeleteId = trackerId) }
    }

    fun onDismissDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete() {
        val trackerId = _uiState.value.pendingDeleteId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteId = null) }

            when (val result = repository.delete(trackerId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.message)
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: TrackerRepository,
            categoryRepository: CategoryRepository,
        ) = viewModelFactory {
            initializer { TrackerListViewModel(repository, categoryRepository) }
        }
    }
}