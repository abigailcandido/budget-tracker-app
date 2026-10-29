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
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.abigailcandido.budgettracker.data.AuthRepository
import com.abigailcandido.budgettracker.data.CategoryRepository
import com.abigailcandido.budgettracker.data.TrackerRepository
import com.abigailcandido.budgettracker.data.TransactionRepository
import com.abigailcandido.budgettracker.data.WalletRepository
import com.abigailcandido.budgettracker.data.local.TokenStore
import com.abigailcandido.budgettracker.ui.auth.AuthScreen
import com.abigailcandido.budgettracker.ui.auth.AuthViewModel
import com.abigailcandido.budgettracker.ui.categories.CategoryListScreen
import com.abigailcandido.budgettracker.ui.categories.CategoryListViewModel
import com.abigailcandido.budgettracker.ui.categories.TrackerPickerScreen
import com.abigailcandido.budgettracker.ui.categories.TrackerPickerViewModel
import com.abigailcandido.budgettracker.ui.categories.GlobalCategoryListViewModel
import com.abigailcandido.budgettracker.ui.categories.GlobalCategoryListScreen
import com.abigailcandido.budgettracker.ui.categories.AddCategoryScreen
import com.abigailcandido.budgettracker.ui.categories.AddGlobalCategoryScreen
import com.abigailcandido.budgettracker.ui.home.HomeScreen
import com.abigailcandido.budgettracker.ui.navigation.CategoryList
import com.abigailcandido.budgettracker.ui.navigation.EntryDetail
import com.abigailcandido.budgettracker.ui.navigation.EntryEdit
import com.abigailcandido.budgettracker.ui.navigation.Home
import com.abigailcandido.budgettracker.ui.navigation.TrackerDetail
import com.abigailcandido.budgettracker.ui.navigation.GlobalCategories
import com.abigailcandido.budgettracker.ui.navigation.TrackerPicker
import com.abigailcandido.budgettracker.ui.navigation.CategoryAdd
import com.abigailcandido.budgettracker.ui.navigation.GlobalCategoryAdd
import com.abigailcandido.budgettracker.ui.navigation.NewTracker
import com.abigailcandido.budgettracker.ui.pin.PinEntryScreen
import com.abigailcandido.budgettracker.ui.pin.PinSetupScreen
import com.abigailcandido.budgettracker.ui.pin.PinViewModel
import com.abigailcandido.budgettracker.ui.theme.BudgetTrackerTheme
import com.abigailcandido.budgettracker.ui.trackers.EntryDetailScreen
import com.abigailcandido.budgettracker.ui.trackers.EntryEditScreen
import com.abigailcandido.budgettracker.ui.trackers.NewTrackerScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerListViewModel
import com.abigailcandido.budgettracker.ui.trackers.TrackerScreen
import com.abigailcandido.budgettracker.ui.trackers.TrackerViewModel

class MainActivity : ComponentActivity() {

    private val tokenStore by lazy { TokenStore(applicationContext) }
    private val authRepository by lazy { AuthRepository(tokenStore) }
    private val trackerRepository by lazy { TrackerRepository(tokenStore) }
    private val walletRepository by lazy { WalletRepository(tokenStore) }
    private val categoryRepository by lazy { CategoryRepository(tokenStore) }

    private val transactionRepository by lazy { TransactionRepository(tokenStore) }

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

                // When the session ends on its own — after a password reset, for
                // example — clear anything left on the PIN screen, so the next
                // sign-in starts from a clean form.
                LaunchedEffect(state.isSignedIn) {
                    if (!state.isSignedIn) pinViewModel.reset()
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
                            onRequestResetLink = pinViewModel::requestResetLink,
                            onSignOut = signOut,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    else -> SignedInNavHost(
                        authRepository = authRepository,
                        trackerRepository = trackerRepository,
                        walletRepository = walletRepository,
                        categoryRepository = categoryRepository,
                        transactionRepository = transactionRepository,
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
    authRepository: AuthRepository,
    trackerRepository: TrackerRepository,
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()

    // The tracker screen and its details and form pages share one ViewModel,
    // so all three make it the same way.
    val trackerFactory = { trackerId: Int ->
        TrackerViewModel.factory(
            trackerId,
            walletRepository,
            categoryRepository,
            transactionRepository,
            authRepository,
        )
    }

    NavHost(navController = navController, startDestination = Home) {

        composable<Home> {
            HomeScreen(
                trackerRepository = trackerRepository,
                categoryRepository = categoryRepository,
                onOpenTracker = { trackerId, trackerName ->
                    navController.navigate(TrackerDetail(trackerId, trackerName))
                },
                onOpenNewTracker = { navController.navigate(NewTracker) },
                onOpenGlobalCategories = { navController.navigate(GlobalCategories) },
                onOpenTrackerCategories = { navController.navigate(TrackerPicker) },
                onSignOut = onSignOut,
            )
        }

        composable<NewTracker> { entry ->
            // The page borrows the tracker list's ViewModel instead of having its
            // own, so creating a tracker updates the very list the user returns to.
            val homeEntry = remember(entry) { navController.getBackStackEntry<Home>() }
            val trackerViewModel: TrackerListViewModel = viewModel(
                viewModelStoreOwner = homeEntry,
                factory = TrackerListViewModel.factory(trackerRepository, categoryRepository),
            )
            val trackerState by trackerViewModel.uiState.collectAsState()

            // `isCreateDialogOpen` now means "the new-tracker form is open". When
            // the ViewModel closes it — after a successful create, or on Cancel —
            // the page closes too. Popping by route makes a second call harmless.
            LaunchedEffect(trackerState.isCreateDialogOpen) {
                if (!trackerState.isCreateDialogOpen) {
                    navController.popBackStack<NewTracker>(inclusive = true)
                }
            }

            NewTrackerScreen(
                state = trackerState,
                onNameChange = trackerViewModel::onNewNameChange,
                onCategoryToggle = trackerViewModel::onCategoryToggle,
                onCreate = trackerViewModel::createTracker,
                onCancel = trackerViewModel::onDismissCreate,
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
                onKindSelected = globalViewModel::onKindSelected,
                onBack = { navController.popBackStack() },
                onAddTopLevelClick = {
                    globalViewModel.onAddTopLevelClick()
                    navController.navigate(GlobalCategoryAdd)
                },
                onAddSubcategoryClick = { parent ->
                    globalViewModel.onAddSubcategoryClick(parent)
                    navController.navigate(GlobalCategoryAdd)
                },
                onToggleExpanded = globalViewModel::onToggleExpanded,
                onDeleteClick = globalViewModel::onDeleteClick,
                onConfirmDelete = globalViewModel::confirmDelete,
                onDismissDelete = globalViewModel::onDismissDelete,
                onDismissActionError = globalViewModel::onDismissActionError,
                onRetry = globalViewModel::refresh,
            )
        }

        composable<GlobalCategoryAdd> { entry ->
            val listEntry = remember(entry) { navController.getBackStackEntry<GlobalCategories>() }
            val globalViewModel: GlobalCategoryListViewModel = viewModel(
                viewModelStoreOwner = listEntry,
                factory = GlobalCategoryListViewModel.factory(
                    categoryRepository,
                    trackerRepository,
                ),
            )
            val globalState by globalViewModel.uiState.collectAsState()

            // The ViewModel clears addRequest after a successful create or on
            // Cancel. Either way the page has nothing left to show, so it closes.
            LaunchedEffect(globalState.addRequest) {
                if (globalState.addRequest == null) {
                    navController.popBackStack<GlobalCategoryAdd>(inclusive = true)
                }
            }

            AddGlobalCategoryScreen(
                state = globalState,
                onNewNameChange = globalViewModel::onNewNameChange,
                onTrackerToggle = globalViewModel::onTrackerToggle,
                onWantsSubcategoriesChange = globalViewModel::onWantsSubcategoriesChange,
                onSubcategoryDraftChange = globalViewModel::onSubcategoryDraftChange,
                onAddSubcategoryName = globalViewModel::onAddSubcategoryName,
                onRemoveSubcategoryName = globalViewModel::onRemoveSubcategoryName,
                onSubmit = globalViewModel::submitAdd,
                onCancel = globalViewModel::onDismissAdd,
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

            val trackerViewModel: TrackerViewModel = viewModel(
                factory = trackerFactory(route.trackerId),
            )
            val trackerState by trackerViewModel.uiState.collectAsState()

            TrackerScreen(
                trackerName = route.trackerName,
                state = trackerState,
                onBack = { navController.popBackStack() },
                onOpenCategories = {
                    navController.navigate(CategoryList(route.trackerId, route.trackerName))
                },
                onPreviousRange = trackerViewModel::onPreviousRange,
                onNextRange = trackerViewModel::onNextRange,
                onRangeSelected = trackerViewModel::onRangeSelected,
                onWalletClick = trackerViewModel::onWalletClick,
                onShowAllWallets = trackerViewModel::onShowAllWallets,
                onFilterSelected = trackerViewModel::onFilterSelected,
                onAddWalletClick = trackerViewModel::onAddWalletClick,
                onWalletNameChange = trackerViewModel::onWalletNameChange,
                onWalletCurrencyChange = trackerViewModel::onWalletCurrencyChange,
                onOpeningBalanceChange = trackerViewModel::onOpeningBalanceChange,
                onConfirmCreateWallet = trackerViewModel::createWallet,
                onDismissCreateWallet = trackerViewModel::onDismissCreateWallet,
                onDeleteWalletClick = trackerViewModel::onDeleteWalletClick,
                onConfirmDeleteWallet = trackerViewModel::confirmDeleteWallet,
                onDismissDeleteWallet = trackerViewModel::onDismissDeleteWallet,
                onDismissActionError = trackerViewModel::onDismissActionError,
                onDismissNotice = trackerViewModel::onDismissNotice,
                onRetry = trackerViewModel::retry,
                onAddEntryClick = {
                    trackerViewModel.onAddEntryClick()
                    // Single top, so a double tap can't open the page twice.
                    navController.navigate(EntryEdit(route.trackerId)) { launchSingleTop = true }
                },
                onEntryClick = { entry ->
                    trackerViewModel.onEntryClick(entry)
                    navController.navigate(EntryDetail(route.trackerId)) { launchSingleTop = true }
                },
            )
        }

        composable<EntryDetail> { entry ->
            val route: EntryDetail = entry.toRoute()
            // Borrows the tracker screen's ViewModel, like the form page below.
            val trackerEntry = remember(entry) { navController.getBackStackEntry<TrackerDetail>() }
            val trackerViewModel: TrackerViewModel = viewModel(
                viewModelStoreOwner = trackerEntry,
                factory = trackerFactory(route.trackerId),
            )
            val trackerState by trackerViewModel.uiState.collectAsState()
            val detail = trackerState.entryDetail

            // Closed by Back. After a correction or a deletion, the form page
            // closes this page along with itself.
            val isDetailOpen = detail != null
            LaunchedEffect(isDetailOpen) {
                if (!isDetailOpen) {
                    navController.popBackStack<EntryDetail>(inclusive = true)
                }
            }

            // The PIN was accepted and the form is ready, so open its page.
            val isFormOpen = trackerState.entryForm != null
            LaunchedEffect(isFormOpen) {
                if (isFormOpen) {
                    navController.navigate(EntryEdit(route.trackerId)) { launchSingleTop = true }
                }
            }

            if (detail != null) {
                EntryDetailScreen(
                    detail = detail,
                    showsCurrency = trackerState.showsCurrency,
                    onBack = trackerViewModel::onDismissEntryDetail,
                    onEditClick = trackerViewModel::onEditEntryClick,
                    onPinChange = trackerViewModel::onEntryPinChange,
                    onConfirmPin = trackerViewModel::confirmEntryPin,
                    onDismissPin = trackerViewModel::onDismissEntryPin,
                    onRetryHistory = trackerViewModel::retryEntryHistory,
                    onDeleteClick = trackerViewModel::onDeleteEntryClick,
                    onDeleteReasonChange = trackerViewModel::onDeleteReasonChange,
                    onSendDeleteCode = trackerViewModel::sendDeleteCode,
                    onDeleteCodeChange = trackerViewModel::onDeleteCodeChange,
                    onConfirmDelete = trackerViewModel::confirmDeleteEntry,
                    onDismissDelete = trackerViewModel::onDismissDelete,
                    onRestoreClick = trackerViewModel::onRestoreEntryClick,
                    onRestoreReasonChange = trackerViewModel::onRestoreReasonChange,
                    onConfirmRestore = trackerViewModel::confirmRestore,
                    onDismissRestore = trackerViewModel::onDismissRestore,
                    onPromoteClick = trackerViewModel::onPromoteEntryClick,
                    onPromoteNameChange = trackerViewModel::onPromoteNameChange,
                    onPromoteWithSubcategoryChange = trackerViewModel::onPromoteWithSubcategoryChange,
                    onPromoteSubcategoryNameChange = trackerViewModel::onPromoteSubcategoryNameChange,
                    onPromoteAsGlobalChange = trackerViewModel::onPromoteAsGlobalChange,
                    onConfirmPromote = trackerViewModel::confirmPromote,
                    onDismissPromote = trackerViewModel::onDismissPromote,
                )
            }
        }

        composable<EntryEdit> { entry ->
            val route: EntryEdit = entry.toRoute()
            // The page borrows the tracker screen's ViewModel instead of having its
            // own, so saving updates the very balances and lists the user goes back to.
            val trackerEntry = remember(entry) { navController.getBackStackEntry<TrackerDetail>() }
            val trackerViewModel: TrackerViewModel = viewModel(
                viewModelStoreOwner = trackerEntry,
                factory = trackerFactory(route.trackerId),
            )
            val trackerState by trackerViewModel.uiState.collectAsState()
            val form = trackerState.entryForm

            // The ViewModel closes the form after a save or a delete, or on Cancel.
            // Either way this page has nothing left to show, so it closes. After a
            // save or a delete the details page has been closed as well, since the
            // version it showed was replaced, so it goes too. A new entry has no
            // details page under it, and that pop simply finds nothing to do.
            val isFormOpen = form != null
            LaunchedEffect(isFormOpen) {
                if (!isFormOpen) {
                    if (trackerState.entryDetail == null) {
                        navController.popBackStack<EntryDetail>(inclusive = true)
                    }
                    navController.popBackStack<EntryEdit>(inclusive = true)
                }
            }

            if (form != null) {
                EntryEditScreen(
                    form = form,
                    wallets = trackerState.wallets,
                    onTypeChange = trackerViewModel::onEntryTypeChange,
                    onWalletChange = trackerViewModel::onEntryWalletChange,
                    onCategoryChange = trackerViewModel::onEntryCategoryChange,
                    onSubcategoryChange = trackerViewModel::onEntrySubcategoryChange,
                    onOtherNameChange = trackerViewModel::onEntryOtherNameChange,
                    onUseCategoryMatch = trackerViewModel::onUseCategoryMatch,
                    onAddToCategoriesChange = trackerViewModel::onAddToCategoriesChange,
                    onWithSubcategoryChange = trackerViewModel::onWithSubcategoryChange,
                    onSubcategoryNameChange = trackerViewModel::onSubcategoryNameChange,
                    onAddAsGlobalChange = trackerViewModel::onAddAsGlobalChange,
                    onAmountChange = trackerViewModel::onEntryAmountChange,
                    onDateChange = trackerViewModel::onEntryDateChange,
                    onNoteChange = trackerViewModel::onEntryNoteChange,
                    onReasonChange = trackerViewModel::onEntryReasonChange,
                    onRetryCategories = trackerViewModel::retryEntryCategories,
                    onSave = trackerViewModel::saveEntry,
                    onCancel = trackerViewModel::onDismissEntryForm,
                )
            }
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
                onKindSelected = categoryViewModel::onKindSelected,
                onBack = { navController.popBackStack() },
                onAddTopLevelClick = {
                    categoryViewModel.onAddTopLevelClick()
                    navController.navigate(CategoryAdd(route.trackerId, route.trackerName))
                },
                onAddSubcategoryClick = { parent ->
                    categoryViewModel.onAddSubcategoryClick(parent)
                    navController.navigate(CategoryAdd(route.trackerId, route.trackerName))
                },
                onToggleExpanded = categoryViewModel::onToggleExpanded,
                onEnabledChange = categoryViewModel::onEnabledChange,
                onDeleteClick = categoryViewModel::onDeleteClick,
                onConfirmDelete = categoryViewModel::confirmDelete,
                onDismissDelete = categoryViewModel::onDismissDelete,
                onDismissActionError = categoryViewModel::onDismissActionError,
                onRetry = categoryViewModel::refresh,
            )
        }

        composable<CategoryAdd> { entry ->
            val route: CategoryAdd = entry.toRoute()
            val listEntry = remember(entry) { navController.getBackStackEntry<CategoryList>() }
            val categoryViewModel: CategoryListViewModel = viewModel(
                viewModelStoreOwner = listEntry,
                factory = CategoryListViewModel.factory(route.trackerId, categoryRepository),
            )
            val categoryState by categoryViewModel.uiState.collectAsState()

            LaunchedEffect(categoryState.addRequest) {
                if (categoryState.addRequest == null) {
                    navController.popBackStack<CategoryAdd>(inclusive = true)
                }
            }

            AddCategoryScreen(
                trackerName = route.trackerName,
                state = categoryState,
                onNewNameChange = categoryViewModel::onNewNameChange,
                onMakeGlobalChange = categoryViewModel::onMakeGlobalChange,
                onWantsSubcategoriesChange = categoryViewModel::onWantsSubcategoriesChange,
                onSubcategoryDraftChange = categoryViewModel::onSubcategoryDraftChange,
                onAddSubcategoryName = categoryViewModel::onAddSubcategoryName,
                onRemoveSubcategoryName = categoryViewModel::onRemoveSubcategoryName,
                onSubmit = categoryViewModel::submitAdd,
                onCancel = categoryViewModel::onDismissAdd,
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