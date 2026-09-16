package com.abigailcandido.budgettracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.ui.auth.AuthScreen
import com.abigailcandido.budgettracker.ui.auth.AuthViewModel
import com.abigailcandido.budgettracker.ui.navigation.TrackerDetail
import com.abigailcandido.budgettracker.ui.navigation.TrackerList
import com.abigailcandido.budgettracker.ui.pin.PinEntryScreen
import com.abigailcandido.budgettracker.ui.pin.PinSetupScreen
import com.abigailcandido.budgettracker.ui.pin.PinViewModel
import com.abigailcandido.budgettracker.ui.theme.BudgetTrackerTheme
import com.abigailcandido.budgettracker.ui.trackers.TrackerDetailScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListViewModel

class MainActivity : ComponentActivity() {

    private val tokenStore by lazy { TokenStore(applicationContext) }
    private val authRepository by lazy { AuthRepository(tokenStore) }
    private val trackerRepository by lazy { TrackerRepository(tokenStore) }

    private val authViewModel: AuthViewModel by viewModels { AuthViewModel.factory(authRepository) }
    private val pinViewModel: PinViewModel by viewModels { PinViewModel.factory(authRepository) }

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

                when {
                    state.isCheckingSession -> LoadingScreen()

                    !state.isSignedIn -> ScreenScaffold { padding ->
                        AuthScreen(
                            state = state,
                            onEmailChange = authViewModel::onEmailChange,
                            onPasswordChange = authViewModel::onPasswordChange,
                            onModeChange = authViewModel::onModeChange,
                            onSubmit = authViewModel::submit,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    !state.hasPin -> ScreenScaffold { padding ->
                        PinSetupScreen(
                            state = pinState,
                            onPinChange = pinViewModel::onPinChange,
                            onConfirmationChange = pinViewModel::onConfirmationChange,
                            onPasswordChange = pinViewModel::onPasswordChange,
                            onSubmit = pinViewModel::setPin,
                            onSignOut = signOut,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    !state.isUnlocked -> ScreenScaffold { padding ->
                        PinEntryScreen(
                            state = pinState,
                            onPinChange = pinViewModel::onPinChange,
                            onSubmit = pinViewModel::verifyPin,
                            onSignOut = signOut,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    else -> SignedInNavHost(
                        trackerRepository = trackerRepository,
                        onSignOut = signOut,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            authViewModel.lock()
            pinViewModel.reset()
        }
    }
}

@Composable
private fun SignedInNavHost(
    trackerRepository: TrackerRepository,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = TrackerList) {

        composable<TrackerList> {
            val listViewModel: TrackerListViewModel = viewModel(
                factory = TrackerListViewModel.factory(trackerRepository),
            )
            val listState by listViewModel.uiState.collectAsState()

            TrackerListScreen(
                state = listState,
                onOpenTracker = { trackerId -> navController.navigate(TrackerDetail(trackerId)) },
                onDeleteTracker = listViewModel::deleteTracker,
                onAddClick = listViewModel::onAddClick,
                onNewNameChange = listViewModel::onNewNameChange,
                onConfirmCreate = listViewModel::createTracker,
                onDismissCreate = listViewModel::onDismissCreate,
                onRetry = listViewModel::refresh,
                onSignOut = onSignOut,
            )
        }

        composable<TrackerDetail> { backStackEntry ->
            val route: TrackerDetail = backStackEntry.toRoute()

            TrackerDetailScreen(
                trackerId = route.trackerId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun ScreenScaffold(content: @Composable (PaddingValues) -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding -> content(padding) }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}