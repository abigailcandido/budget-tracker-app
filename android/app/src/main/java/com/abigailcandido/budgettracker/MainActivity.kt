package com.abigailcandido.budgettracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.ui.auth.AuthScreen
import com.abigailcandido.budgettracker.ui.auth.AuthViewModel
import com.abigailcandido.budgettracker.ui.theme.BudgetTrackerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = AuthRepository(TokenStore(applicationContext))

        setContent {
            BudgetTrackerTheme {
                val viewModel: AuthViewModel = viewModel(
                    factory = AuthViewModel.factory(repository),
                )
                val state by viewModel.uiState.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when {
                        state.isCheckingSession -> LoadingScreen(
                            modifier = Modifier.padding(innerPadding),
                        )

                        state.isSignedIn -> SignedInPlaceholder(
                            onSignOut = viewModel::signOut,
                            modifier = Modifier.padding(innerPadding),
                        )

                        else -> AuthScreen(
                            state = state,
                            onEmailChange = viewModel::onEmailChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onModeChange = viewModel::onModeChange,
                            onSubmit = viewModel::submit,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
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