package com.abigailcandido.budgettracker.ui.categories

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

data class TrackerPickerUiState(
    val isLoading: Boolean = true,
    val trackers: List<TrackerResponse> = emptyList(),
    val errorMessage: String? = null,
)

class TrackerPickerViewModel(
    private val repository: TrackerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackerPickerUiState())
    val uiState: StateFlow<TrackerPickerUiState> = _uiState.asStateFlow()

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

    companion object {
        fun factory(repository: TrackerRepository) = viewModelFactory {
            initializer { TrackerPickerViewModel(repository) }
        }
    }
}