package com.abigailcandido.budgettracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.ui.auth.AuthScreen
import com.abigailcandido.budgettracker.ui.auth.AuthViewModel
import com.abigailcandido.budgettracker.ui.pin.PinEntryScreen
import com.abigailcandido.budgettracker.ui.pin.PinSetupScreen
import com.abigailcandido.budgettracker.ui.pin.PinViewModel
import com.abigailcandido.budgettracker.ui.theme.BudgetTrackerTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy { AuthRepository(TokenStore(applicationContext)) }

    private val authViewModel: AuthViewModel by viewModels { AuthViewModel.factory(repository) }
    private val pinViewModel: PinViewModel by viewModels { PinViewModel.factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BudgetTrackerTheme {
                val state by authViewModel.uiState.collectAsState()
                val pinState by pinViewModel.uiState.collectAsState()

                val signOut: () -> Unit = {
                    pinViewModel.reset()
                    authViewModel.signOut()
                }

                LaunchedEffect(pinState.completed) {
                    if (pinState.completed) {
                        if (state.hasPin) {
                            authViewModel.onPinVerified()
                        } else {
                            authViewModel.onPinSet()
                        }
                        pinViewModel.reset()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val screenModifier = Modifier.padding(innerPadding)

                    when {
                        state.isCheckingSession -> LoadingScreen(screenModifier)

                        !state.isSignedIn -> AuthScreen(
                            state = state,
                            onEmailChange = authViewModel::onEmailChange,
                            onPasswordChange = authViewModel::onPasswordChange,
                            onModeChange = authViewModel::onModeChange,
                            onSubmit = authViewModel::submit,
                            modifier = screenModifier,
                        )

                        !state.hasPin -> PinSetupScreen(
                            state = pinState,
                            onPinChange = pinViewModel::onPinChange,
                            onConfirmationChange = pinViewModel::onConfirmationChange,
                            onPasswordChange = pinViewModel::onPasswordChange,
                            onSubmit = pinViewModel::setPin,
                            onSignOut = signOut,
                            modifier = screenModifier,
                        )

                        !state.isUnlocked -> PinEntryScreen(
                            state = pinState,
                            onPinChange = pinViewModel::onPinChange,
                            onSubmit = pinViewModel::verifyPin,
                            onSignOut = signOut,
                            modifier = screenModifier,
                        )

                        else -> SignedInPlaceholder(
                            onSignOut = signOut,
                            modifier = screenModifier,
                        )
                    }
                }
            }
        }
    }

    /**
     * Re-lock whenever the app leaves the screen, but not when it is merely being
     * recreated for a configuration change. Without that check, rotating the phone
     * would demand the PIN again, which users read as a bug rather than security.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            authViewModel.lock()
            pinViewModel.reset()
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SignedInPlaceholder(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "You are signed in.",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Trackers, wallets and transactions come next.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignOut) {
            Text("Sign out")
        }
    }
}