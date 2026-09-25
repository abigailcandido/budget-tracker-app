package com.abigailcandido.budgettracker.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.remote.ApiResult
import io.ktor.http.HttpStatusCode
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
    val isCheckingSession: Boolean = true,
    val isSignedIn: Boolean = false,
    val hasPin: Boolean = false,
    val isUnlocked: Boolean = false,
) {
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
        refreshSession()
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

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(password = "") }
                    refreshSession(justAuthenticated = true)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = result.message)
                }
            }
        }
    }

    /** Called when the PIN setup screen reports success. */
    fun onPinSet() {
        _uiState.update { it.copy(hasPin = true, isUnlocked = true) }
    }

    /** Called when the PIN entry screen reports a correct PIN. */
    fun onPinVerified() {
        _uiState.update { it.copy(isUnlocked = true) }
    }

    /**
     * Re-locks the app. Called when it goes to the background, so returning to it
     * requires the PIN again — which is the whole point of having one on top of a
     * session that deliberately never expires.
     */
    fun lock() {
        _uiState.update { if (it.hasPin) it.copy(isUnlocked = false) else it }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState(isCheckingSession = false)
        }
    }

    /**
     * Asks the server who we are. This does more than fetch `hasPin`: it is also
     * how a token that the server has already discarded gets detected, since the
     * repository clears local credentials on a 401 and we fall back to the login
     * screen rather than showing a signed-in UI backed by a dead session.
     */
    private fun refreshSession(justAuthenticated: Boolean = false) {
        viewModelScope.launch {
            if (!repository.isSignedIn()) {
                _uiState.update {
                    it.copy(isCheckingSession = false, isSubmitting = false, isSignedIn = false)
                }
                return@launch
            }

            when (val result = repository.currentUser()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isCheckingSession = false,
                        isSubmitting = false,
                        isSignedIn = true,
                        hasPin = result.data.hasPin,
                        // The password is a stronger proof than the PIN, so someone
                        // who has just typed it doesn't need to prove themselves again.
                        // On a cold start there's no password, so the PIN gate applies.
                        isUnlocked = justAuthenticated,
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        isCheckingSession = false,
                        isSubmitting = false,
                        isSignedIn = false,
                        // A 401 simply means the session ended; that is not an error
                        // worth showing someone who is about to see a login form.
                        errorMessage = if (result.status == HttpStatusCode.Unauthorized) {
                            null
                        } else {
                            result.message
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun factory(repository: AuthRepository) = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}