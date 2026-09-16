package com.abigailcandido.budgettracker.ui.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val PIN_MIN_LENGTH = 4
internal const val PIN_MAX_LENGTH = 6

data class PinUiState(
    val pin: String = "",
    val confirmation: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val completed: Boolean = false,
) {
    val canSetPin: Boolean
        get() = !isSubmitting &&
                pin.length >= PIN_MIN_LENGTH &&
                confirmation == pin &&
                password.isNotBlank()

    val canVerifyPin: Boolean
        get() = !isSubmitting && pin.length >= PIN_MIN_LENGTH
}

class PinViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PinUiState())
    val uiState: StateFlow<PinUiState> = _uiState.asStateFlow()

    fun onPinChange(value: String) {
        if (!value.all(Char::isDigit)) return
        _uiState.update { it.copy(pin = value.take(PIN_MAX_LENGTH), errorMessage = null) }
    }

    fun onConfirmationChange(value: String) {
        if (!value.all(Char::isDigit)) return
        _uiState.update { it.copy(confirmation = value.take(PIN_MAX_LENGTH), errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun setPin() {
        val current = _uiState.value
        if (!current.canSetPin) return
        submit { repository.setPin(current.password, current.pin) }
    }

    fun verifyPin() {
        val current = _uiState.value
        if (!current.canVerifyPin) return
        submit { repository.verifyPin(current.pin) }
    }

    fun reset() {
        _uiState.value = PinUiState()
    }

    private fun submit(call: suspend () -> ApiResult<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            when (val result = call()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isSubmitting = false, completed = true)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        pin = "",
                        confirmation = "",
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: AuthRepository) = viewModelFactory {
            initializer { PinViewModel(repository) }
        }
    }
}