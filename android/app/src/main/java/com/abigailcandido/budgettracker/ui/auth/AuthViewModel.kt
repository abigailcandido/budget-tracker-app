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

private const val SESSION_ENDED_NOTICE = "You've been signed out. Sign in again to continue."

enum class AuthMode { SignIn, Register, ResetPassword }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    // Something worth telling the user that is not a problem, such as a reset
    // link being on its way. Shown in a calmer style than errorMessage.
    val notice: String? = null,
    val isCheckingSession: Boolean = true,
    val isSignedIn: Boolean = false,
    val hasPin: Boolean = false,
    val isUnlocked: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && email.isNotBlank() && when (mode) {
            AuthMode.SignIn -> password.isNotBlank()
            AuthMode.Register -> password.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH
            AuthMode.ResetPassword -> true
        }
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        refreshSession()
        watchForEndedSession()
    }

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null, notice = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, notice = null) }
    }

    fun onModeChange(mode: AuthMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null, notice = null) }
    }

    fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return

        if (current.mode == AuthMode.ResetPassword) {
            sendPasswordResetLink(current.email)
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, notice = null) }

            val result = if (current.mode == AuthMode.Register) {
                repository.register(current.email, current.password)
            } else {
                repository.login(current.email, current.password)
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

    private fun sendPasswordResetLink(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, notice = null) }

            when (val result = repository.requestPasswordReset(email)) {
                // Back to the sign-in form with the email still filled in, ready
                // for the new password once it has been chosen.
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        mode = AuthMode.SignIn,
                        password = "",
                        notice = result.data,
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = result.message)
                }
            }
        }
    }

    /**
     * Moves the app back to the sign-in screen if the stored session disappears
     * while it thinks it is signed in. Without this, a session ended elsewhere —
     * by a password reset, for example — would leave the app on a screen where
     * every request fails until it was closed and reopened.
     */
    private fun watchForEndedSession() {
        viewModelScope.launch {
            repository.hasSession.collect { hasSession ->
                if (!hasSession && _uiState.value.isSignedIn) {
                    _uiState.value = AuthUiState(
                        isCheckingSession = false,
                        notice = SESSION_ENDED_NOTICE,
                    )
                }
            }
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