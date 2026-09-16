package com.abigailcandido.budgettracker.ui.pin

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

@Composable
fun PinSetupScreen(
    state: PinUiState,
    onPinChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mismatch = state.confirmation.isNotEmpty() && state.confirmation != state.pin

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set up a PIN", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "You'll enter this each time you open the app, so your finances " +
                    "stay private even if someone else picks up your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        PinField(
            value = state.pin,
            onValueChange = onPinChange,
            label = "PIN",
            enabled = !state.isSubmitting,
            isError = state.errorMessage != null,
            supportingText = "$PIN_MIN_LENGTH to $PIN_MAX_LENGTH digits",
            imeAction = ImeAction.Next,
        )

        Spacer(Modifier.height(16.dp))

        PinField(
            value = state.confirmation,
            onValueChange = onConfirmationChange,
            label = "Confirm PIN",
            enabled = !state.isSubmitting,
            isError = mismatch,
            supportingText = if (mismatch) "PINs do not match" else null,
            imeAction = ImeAction.Next,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Your account password") },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.errorMessage != null,
            supportingText = { Text("Confirms it's really you setting this PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        ErrorText(state.errorMessage)

        Spacer(Modifier.height(24.dp))

        SubmitButton(
            label = "Save PIN",
            enabled = state.canSetPin,
            isSubmitting = state.isSubmitting,
            onClick = onSubmit,
        )

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onSignOut, enabled = !state.isSubmitting) {
            Text("Sign out instead")
        }
    }
}

@Composable
fun PinEntryScreen(
    state: PinUiState,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Enter your PIN", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Welcome back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        PinField(
            value = state.pin,
            onValueChange = onPinChange,
            label = "PIN",
            enabled = !state.isSubmitting,
            isError = state.errorMessage != null,
            supportingText = null,
            imeAction = ImeAction.Done,
            onDone = onSubmit,
        )

        ErrorText(state.errorMessage)

        Spacer(Modifier.height(24.dp))

        SubmitButton(
            label = "Unlock",
            enabled = state.canVerifyPin,
            isSubmitting = state.isSubmitting,
            onClick = onSubmit,
        )

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onSignOut, enabled = !state.isSubmitting) {
            Text("Forgot your PIN? Sign out")
        }
    }
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?,
    imeAction: ImeAction,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SubmitButton(
    label: String,
    enabled: Boolean,
    isSubmitting: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label)
        }
    }
}