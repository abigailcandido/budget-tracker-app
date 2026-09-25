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
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.data.WalletRepository
import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.ui.auth.AuthScreen
import com.abigailcandido.budgettracker.ui.auth.AuthViewModel
import com.abigailcandido.budgettracker.ui.categories.CategoryListScreen
import com.abigailcandido.budgettracker.ui.categories.CategoryListViewModel
import com.abigailcandido.budgettracker.ui.home.HomeScreen
import com.abigailcandido.budgettracker.ui.navigation.CategoryList
import com.abigailcandido.budgettracker.ui.navigation.Home
import com.abigailcandido.budgettracker.ui.navigation.TrackerDetail
import com.abigailcandido.budgettracker.ui.pin.PinEntryScreen
import com.abigailcandido.budgettracker.ui.pin.PinSetupScreen
import com.abigailcandido.budgettracker.ui.pin.PinViewModel
import com.abigailcandido.budgettracker.ui.theme.BudgetTrackerTheme
import com.abigailcandido.budgettracker.ui.wallets.WalletListScreen
import com.abigailcandido.budgettracker.ui.wallets.WalletListViewModel
import com.abigailcandido.budgettracker.ui.categories.TrackerPickerScreen
import com.abigailcandido.budgettracker.ui.categories.TrackerPickerViewModel
import com.abigailcandido.budgettracker.ui.navigation.GlobalCategories
import com.abigailcandido.budgettracker.ui.navigation.TrackerPicker
import com.abigailcandido.budgettracker.ui.categories.GlobalCategoryListViewModel
import com.abigailcandido.budgettracker.ui.categories.GlobalCategoryListScreen

class MainActivity : ComponentActivity() {

    private val tokenStore by lazy { TokenStore(applicationContext) }
    private val authRepository by lazy { AuthRepository(tokenStore) }
    private val trackerRepository by lazy { TrackerRepository(tokenStore) }
    private val walletRepository by lazy { WalletRepository(tokenStore) }
    private val categoryRepository by lazy { CategoryRepository(tokenStore) }

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
                        walletRepository = walletRepository,
                        categoryRepository = categoryRepository,
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
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Home) {

        composable<Home> {
            HomeScreen(
                trackerRepository = trackerRepository,
                categoryRepository = categoryRepository,
                onOpenTracker = { trackerId, trackerName ->
                    navController.navigate(TrackerDetail(trackerId, trackerName))
                },
                onOpenGlobalCategories = { navController.navigate(GlobalCategories) },
                onOpenTrackerCategories = { navController.navigate(TrackerPicker) },
                onSignOut = onSignOut,
            )
        }

        composable<GlobalCategories> {
            val globalViewModel: GlobalCategoryListViewModel = viewModel(
                factory = GlobalCategoryListViewModel.factory(
                    categoryRepository,
                    trackerRepository,
                ),
            )
            val globalState by globalViewModel.uiState.collectAsState()

            GlobalCategoryListScreen(
                state = globalState,
                onBack = { navController.popBackStack() },
                onAddTopLevelClick = globalViewModel::onAddTopLevelClick,
                onAddSubcategoryClick = globalViewModel::onAddSubcategoryClick,
                onToggleExpanded = globalViewModel::onToggleExpanded,
                onNewNameChange = globalViewModel::onNewNameChange,
                onTrackerToggle = globalViewModel::onTrackerToggle,
                onWantsSubcategoriesChange = globalViewModel::onWantsSubcategoriesChange,
                onSubcategoryDraftChange = globalViewModel::onSubcategoryDraftChange,
                onAddSubcategoryName = globalViewModel::onAddSubcategoryName,
                onRemoveSubcategoryName = globalViewModel::onRemoveSubcategoryName,
                onSubmitAdd = globalViewModel::submitAdd,
                onDismissAdd = globalViewModel::onDismissAdd,
                onDeleteClick = globalViewModel::onDeleteClick,
                onConfirmDelete = globalViewModel::confirmDelete,
                onDismissDelete = globalViewModel::onDismissDelete,
                onDismissActionError = globalViewModel::onDismissActionError,
                onRetry = globalViewModel::refresh,
            )
        }

        composable<TrackerPicker> {
            val pickerViewModel: TrackerPickerViewModel = viewModel(
                factory = TrackerPickerViewModel.factory(trackerRepository),
            )
            val pickerState by pickerViewModel.uiState.collectAsState()

            TrackerPickerScreen(
                state = pickerState,
                onBack = { navController.popBackStack() },
                onPickTracker = { trackerId, trackerName ->
                    navController.navigate(CategoryList(trackerId, trackerName))
                },
                onRetry = pickerViewModel::refresh,
            )
        }

        composable<TrackerDetail> { backStackEntry ->
            val route: TrackerDetail = backStackEntry.toRoute()

            val walletViewModel: WalletListViewModel = viewModel(
                factory = WalletListViewModel.factory(route.trackerId, walletRepository),
            )
            val walletState by walletViewModel.uiState.collectAsState()

            WalletListScreen(
                trackerName = route.trackerName,
                state = walletState,
                onBack = { navController.popBackStack() },
                onOpenCategories = {
                    navController.navigate(CategoryList(route.trackerId, route.trackerName))
                },
                onAddClick = walletViewModel::onAddClick,
                onNameChange = walletViewModel::onNameChange,
                onCurrencyChange = walletViewModel::onCurrencyChange,
                onOpeningBalanceChange = walletViewModel::onOpeningBalanceChange,
                onConfirmCreate = walletViewModel::createWallet,
                onDismissCreate = walletViewModel::onDismissCreate,
                onDeleteClick = walletViewModel::onDeleteClick,
                onConfirmDelete = walletViewModel::confirmDelete,
                onDismissDelete = walletViewModel::onDismissDelete,
                onRetry = walletViewModel::refresh,
            )
        }

        composable<CategoryList> { backStackEntry ->
            val route: CategoryList = backStackEntry.toRoute()

            val categoryViewModel: CategoryListViewModel = viewModel(
                factory = CategoryListViewModel.factory(route.trackerId, categoryRepository),
            )
            val categoryState by categoryViewModel.uiState.collectAsState()

            CategoryListScreen(
                trackerName = route.trackerName,
                state = categoryState,
                onBack = { navController.popBackStack() },
                onAddTopLevelClick = categoryViewModel::onAddTopLevelClick,
                onAddSubcategoryClick = categoryViewModel::onAddSubcategoryClick,
                onToggleExpanded = categoryViewModel::onToggleExpanded,
                onNewNameChange = categoryViewModel::onNewNameChange,
                onMakeGlobalChange = categoryViewModel::onMakeGlobalChange,
                onWantsSubcategoriesChange = categoryViewModel::onWantsSubcategoriesChange,
                onSubcategoryDraftChange = categoryViewModel::onSubcategoryDraftChange,
                onAddSubcategoryName = categoryViewModel::onAddSubcategoryName,
                onRemoveSubcategoryName = categoryViewModel::onRemoveSubcategoryName,
                onSubmitAdd = categoryViewModel::submitAdd,
                onDismissAdd = categoryViewModel::onDismissAdd,
                onEnabledChange = categoryViewModel::onEnabledChange,
                onDeleteClick = categoryViewModel::onDeleteClick,
                onConfirmDelete = categoryViewModel::confirmDelete,
                onDismissDelete = categoryViewModel::onDismissDelete,
                onDismissActionError = categoryViewModel::onDismissActionError,
                onRetry = categoryViewModel::refresh,
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