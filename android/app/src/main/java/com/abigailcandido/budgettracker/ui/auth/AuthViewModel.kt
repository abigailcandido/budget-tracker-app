package com.abigailcandido.budgettracker.ui.auth

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

internal const val MIN_PASSWORD_LENGTH = 8

enum class AuthMode { SignIn, Register }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val isSignedIn: Boolean = false,
    val isCheckingSession: Boolean = true,
) {
    /**
     * The minimum length is enforced when registering, because that mirrors what
     * the server will accept. It is deliberately not enforced when signing in —
     * inventing a client-side rule there would lock out any account whose
     * password predates the rule, and the server is the authority either way.
     */
    val canSubmit: Boolean
        get() = !isSubmitting &&
                email.isNotBlank() &&
                password.isNotBlank() &&
                (mode == AuthMode.SignIn || password.length >= MIN_PASSWORD_LENGTH)
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val signedIn = repository.isSignedIn()
            _uiState.update { it.copy(isSignedIn = signedIn, isCheckingSession = false) }
        }
    }

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onModeChange(mode: AuthMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null) }
    }

    fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = when (current.mode) {
                AuthMode.SignIn -> repository.login(current.email, current.password)
                AuthMode.Register -> repository.register(current.email, current.password)
            }

            _uiState.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        isSubmitting = false,
                        isSignedIn = true,
                        password = "",
                    )
                    is ApiResult.Failure -> state.copy(
                        isSubmitting = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState(isCheckingSession = false)
        }
    }

    companion object {
        fun factory(repository: AuthRepository) = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}