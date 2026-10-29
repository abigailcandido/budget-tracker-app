package com.abigailcandido.budgettracker.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.ui.common.AppVersionText

@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = state.mode
    val error = state.errorMessage
    val notice = state.notice

    // From the registration or reset form, the back gesture returns to signing
    // in instead of closing the app.
    BackHandler(enabled = mode != AuthMode.SignIn && !state.isSubmitting) {
        onModeChange(AuthMode.SignIn)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (mode) {
                AuthMode.SignIn -> "Welcome back"
                AuthMode.Register -> "Create your account"
                AuthMode.ResetPassword -> "Reset your password"
            },
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = when (mode) {
                AuthMode.SignIn -> "Sign in to continue."
                AuthMode.Register -> "Track your wallets, bills and savings in one place."
                AuthMode.ResetPassword ->
                    "Enter your account email and we'll send you a link to choose a new password."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = if (mode == AuthMode.ResetPassword) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (mode != AuthMode.ResetPassword) {
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.isSubmitting,
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                supportingText = if (mode == AuthMode.Register) {
                    { Text("At least $MIN_PASSWORD_LENGTH characters") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    when (mode) {
                        AuthMode.SignIn -> "Sign in"
                        AuthMode.Register -> "Create account"
                        AuthMode.ResetPassword -> "Send reset link"
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (mode) {
            AuthMode.SignIn -> {
                TextButton(
                    onClick = { onModeChange(AuthMode.ResetPassword) },
                    enabled = !state.isSubmitting,
                ) {
                    Text("Forgot password?")
                }
                TextButton(
                    onClick = { onModeChange(AuthMode.Register) },
                    enabled = !state.isSubmitting,
                ) {
                    Text("New here? Create an account")
                }
            }
            AuthMode.Register -> TextButton(
                onClick = { onModeChange(AuthMode.SignIn) },
                enabled = !state.isSubmitting,
            ) {
                Text("I already have an account")
            }
            AuthMode.ResetPassword -> TextButton(
                onClick = { onModeChange(AuthMode.SignIn) },
                enabled = !state.isSubmitting,
            ) {
                Text("Back to sign in")
            }
        }

        Spacer(Modifier.height(24.dp))
        AppVersionText()
    }
}