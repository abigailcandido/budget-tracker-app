package com.abigailcandido.budgettracker.ui.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
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
) {
    val canCreate: Boolean
        get() = !isCreating && newName.isNotBlank()
}

class TrackerListViewModel(private val repository: TrackerRepository) : ViewModel() {

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
            it.copy(isCreateDialogOpen = true, newName = "", createError = null)
        }
    }

    fun onDismissCreate() {
        // Ignored mid-request, so a stray tap outside the dialog cannot close it
        // while a tracker is being created and leave the result unexplained.
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreateDialogOpen = false, createError = null) }
    }

    fun onNewNameChange(value: String) {
        _uiState.update { it.copy(newName = value, createError = null) }
    }

    fun createTracker() {
        val current = _uiState.value
        if (!current.canCreate) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }

            when (val result = repository.create(current.newName)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isCreating = false, isCreateDialogOpen = false, newName = "")
                    }
                    refresh()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isCreating = false, createError = result.message)
                }
            }
        }
    }

    fun deleteTracker(trackerId: Int) {
        viewModelScope.launch {
            when (val result = repository.delete(trackerId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _uiState.update {
                    it.copy(errorMessage = result.message)
                }
            }
        }
    }

    companion object {
        fun factory(repository: TrackerRepository) = viewModelFactory {
            initializer { TrackerListViewModel(repository) }
        }
    }
}