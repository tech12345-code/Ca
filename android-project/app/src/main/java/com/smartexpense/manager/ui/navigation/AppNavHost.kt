package com.smartexpense.manager.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.security.PinSecurityEngine
import com.smartexpense.manager.ui.components.BalanceExhaustedDialog
import com.smartexpense.manager.ui.components.InsufficientBalanceDialog
import com.smartexpense.manager.ui.screens.AddTransactionSheet
import com.smartexpense.manager.ui.screens.AnalyticsScreen
import com.smartexpense.manager.ui.screens.AppLockScreen
import com.smartexpense.manager.ui.screens.DashboardScreen
import com.smartexpense.manager.ui.screens.LedgerScreen
import com.smartexpense.manager.ui.screens.OnboardingScreen
import com.smartexpense.manager.ui.screens.PdfExportScreen
import com.smartexpense.manager.ui.screens.ReceiptImportScreen
import com.smartexpense.manager.ui.screens.SecuritySettingsScreen
import com.smartexpense.manager.ui.screens.SettingsScreen
import com.smartexpense.manager.ui.screens.SplitExpensesScreen
import com.smartexpense.manager.ui.theme.SmartColors
import com.smartexpense.manager.ui.viewmodel.MainViewModel

@Composable
fun AppNavHost(
    sharedReceiptUri: Uri?,
    deepLinkRoute: String?,
    onConsumeSharedUri: () -> Unit,
    onConsumeDeepLink: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val uid by viewModel.currentUid.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val summary by viewModel.balanceSummary.collectAsStateWithLifecycle()
    val securitySettings by viewModel.securitySettings.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val insufficientBalanceAlert by viewModel.insufficientBalanceAlert.collectAsStateWithLifecycle()
    val balanceExhaustedAlert by viewModel.balanceExhaustedAlert.collectAsStateWithLifecycle()
    val biometricCapability = remember(securitySettings.securityConfigured) {
        viewModel.getBiometricCapability()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheetType by remember { mutableStateOf<TransactionType?>(null) }
    var presetSheetCategory by remember { mutableStateOf<ExpenseCategory?>(null) }
    var editingTransaction by remember { mutableStateOf<Transaction?>(null) }

    val canDispatchDeepLinks = PinSecurityEngine.canDispatchDeepLink(
        securityConfigured = securitySettings.securityConfigured,
        appLockEnabled = securitySettings.appLockEnabled,
        method = securitySettings.method,
        isUnlocked = isAppUnlocked
    ) && uid != null && profile?.setupCompleted == true

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.clearFeedback()
        }
    }

    // Security guarantee: Shared receipt URIs and notification deep-links stay queued until unlocked
    LaunchedEffect(sharedReceiptUri, canDispatchDeepLinks) {
        if (sharedReceiptUri != null && canDispatchDeepLinks) {
            viewModel.scanReceiptUri(context, sharedReceiptUri)
            navController.navigate("receipt_import")
            onConsumeSharedUri()
        }
    }

    LaunchedEffect(deepLinkRoute, canDispatchDeepLinks) {
        if (deepLinkRoute != null && canDispatchDeepLinks) {
            val safeRoute = when (deepLinkRoute) {
                "dashboard", "ledger", "split_expenses", "analytics", "receipt_import", "pdf_export", "settings", "security_settings" -> deepLinkRoute
                else -> "dashboard"
            }
            navController.navigate(safeRoute)
            onConsumeDeepLink()
        }
    }

    if (uid == null || profile?.setupCompleted != true || !securitySettings.securityConfigured) {
        OnboardingScreen(
            isSignedIn = uid != null,
            budgetSetupCompleted = profile?.setupCompleted == true,
            biometricCapability = biometricCapability,
            onGoogleIdToken = { token, name, email ->
                viewModel.signInWithGoogleIdToken(token, name, email)
            },
            onCompleteBudgetSetup = { budget ->
                viewModel.completeFirstTimeSetup(budget)
            },
            onCompleteSecuritySetup = { method, pin, confirmPin, fingerprintQuick ->
                viewModel.configureFirstTimeSecurity(
                    method = method,
                    pin = pin,
                    confirmPin = confirmPin,
                    enableBiometricQuickUnlock = fingerprintQuick
                )
            },
            onTriggerDeviceLockTest = { activity, onSuccess, onError ->
                viewModel.launchSystemBiometricUnlock(
                    activity = activity,
                    title = "Confirm Phone Screen Lock",
                    subtitle = "Verify your fingerprint or device screen lock to protect Smart Expense Manager",
                    allowDeviceCredential = true,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
        )
        return
    }

    // Security guarantee: Dashboard and financial ledgers are NEVER composed behind the lock screen
    if (securitySettings.appLockEnabled && !isAppUnlocked) {
        AppLockScreen(
            userEmail = profile?.email.orEmpty(),
            settings = securitySettings,
            biometricCapability = biometricCapability,
            onVerifyPin = { pin -> viewModel.verifySemPin(pin) },
            onTriggerBiometricUnlock = { activity, allowDeviceCred, onErr ->
                viewModel.launchSystemBiometricUnlock(
                    activity = activity,
                    allowDeviceCredential = allowDeviceCred,
                    onError = onErr
                )
            },
            onResetPinWithGoogleReauth = { token, newPin, confirmPin, callback ->
                viewModel.resetForgottenPinWithGoogleReauth(token, newPin, confirmPin, callback)
            },
            onResetPinAfterDeviceAuth = { newPin, confirmPin ->
                viewModel.resetForgottenPinAfterDeviceAuth(newPin, confirmPin)
            }
        )
        return
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "dashboard"

    val bottomItems = listOf(
        Triple("dashboard", "Home", Icons.Rounded.Home),
        Triple("ledger", "Ledger", Icons.Rounded.AccountBalanceWallet),
        Triple("split_expenses", "Splits", Icons.Rounded.Groups),
        Triple("analytics", "Analytics", Icons.Rounded.Analytics),
        Triple("settings", "Settings", Icons.Rounded.Settings)
    )

    Scaffold(
        containerColor = SmartColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = SmartColors.Surface) {
                bottomItems.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, maxLines = 1, softWrap = false) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SmartColors.Background,
                            selectedTextColor = SmartColors.NeonLime,
                            indicatorColor = SmartColors.NeonLime,
                            unselectedIconColor = SmartColors.TextMuted,
                            unselectedTextColor = SmartColors.TextMuted
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    profile = profile!!,
                    summary = summary,
                    onAddExpense = { presetCat ->
                        editingTransaction = null
                        presetSheetCategory = presetCat
                        activeSheetType = TransactionType.EXPENSE
                    },
                    onAddBalance = {
                        editingTransaction = null
                        presetSheetCategory = null
                        activeSheetType = TransactionType.INCOME
                    },
                    onImportReceipt = { navController.navigate("receipt_import") },
                    onOpenLedger = { navController.navigate("ledger") },
                    onSelectTransaction = { tx ->
                        editingTransaction = tx
                        activeSheetType = tx.type
                    }
                )
            }
            composable("ledger") {
                val filter by viewModel.transactionFilter.collectAsStateWithLifecycle()
                val filteredState by viewModel.filteredLedgerState.collectAsStateWithLifecycle()
                val paymentMethods by viewModel.availablePaymentMethods.collectAsStateWithLifecycle()
                LedgerScreen(
                    currency = profile!!.currencySymbol,
                    state = filteredState,
                    filter = filter,
                    availablePaymentMethods = paymentMethods,
                    onSearchChange = viewModel::updateSearchQuery,
                    onDatePresetSelect = viewModel::setDatePreset,
                    onCustomDateRange = viewModel::setCustomDateRange,
                    onToggleCategory = viewModel::toggleCategoryFilter,
                    onClearCategories = viewModel::clearCategoriesFilter,
                    onTogglePaymentMethod = viewModel::togglePaymentMethodFilter,
                    onClearPaymentMethods = viewModel::clearPaymentMethodsFilter,
                    onSelectType = viewModel::setTypeFilter,
                    onClearAllFilters = viewModel::clearAllFilters,
                    onEditTransaction = { tx ->
                        editingTransaction = tx
                        activeSheetType = tx.type
                    },
                    onDeleteTransaction = { txId -> viewModel.deleteTransaction(txId) },
                    onOpenPdfExport = { navController.navigate("pdf_export") }
                )
            }
            composable("receipt_import") {
                ReceiptImportScreen(
                    viewModel = viewModel,
                    currency = profile!!.currencySymbol,
                    onSaved = { navController.navigate("ledger") }
                )
            }
            composable("split_expenses") {
                SplitExpensesScreen(
                    viewModel = viewModel,
                    profile = profile!!
                )
            }
            composable("analytics") {
                AnalyticsScreen(
                    currency = profile!!.currencySymbol,
                    summary = summary,
                    onOpenPdfExport = { navController.navigate("pdf_export") }
                )
            }
            composable("pdf_export") {
                PdfExportScreen(
                    onExportPdf = { start, end -> viewModel.exportPdfStatement(start, end) }
                )
            }
            composable("settings") {
                SettingsScreen(
                    profile = profile!!,
                    securitySettings = securitySettings,
                    onUpdateBudget = { viewModel.updateMonthlyBudget(it) },
                    onUpdatePreferences = { viewModel.updateNotificationPreferences(it) },
                    onOpenSecuritySettings = { navController.navigate("security_settings") },
                    onOpenPdfExport = { navController.navigate("pdf_export") },
                    onBackupNow = { viewModel.backupNow() },
                    onRestoreCloud = { viewModel.restoreFromCloud() },
                    onSignOut = { viewModel.signOut() }
                )
            }
            composable("security_settings") {
                SecuritySettingsScreen(
                    userEmail = profile!!.email,
                    settings = securitySettings,
                    biometricCapability = biometricCapability,
                    onBack = { navController.popBackStack() },
                    onToggleAppLock = { viewModel.setAppLockEnabled(it) },
                    onSelectMethod = { viewModel.updateSecurityMethod(it) },
                    onChangeSemPin = { cur, next, confirm -> viewModel.changeSemPin(cur, next, confirm) },
                    onCreateSemPin = { next, confirm -> viewModel.setupOrReplaceSemPinFromSettings(next, confirm) },
                    onToggleFingerprintQuickUnlock = { viewModel.setFingerprintQuickUnlock(it) },
                    onSelectTimeout = { viewModel.setAutoLockTimeout(it) },
                    onToggleLockOnClose = { viewModel.setLockOnAppClose(it) },
                    onLockNow = { viewModel.lockAppNow() }
                )
            }
        }
    }

    if (activeSheetType != null) {
        AddTransactionSheet(
            initialType = activeSheetType!!,
            presetCategory = presetSheetCategory,
            existing = editingTransaction,
            onDismiss = {
                activeSheetType = null
                presetSheetCategory = null
                editingTransaction = null
            },
            onSave = { type, amount, desc, merchant, category, customDetail, paymentMethod, source, note, date, time ->
                val result = viewModel.saveTransaction(
                    existingId = editingTransaction?.id,
                    type = type,
                    amount = amount,
                    description = desc,
                    merchant = merchant,
                    category = category,
                    customCategoryDetail = customDetail,
                    paymentMethod = paymentMethod,
                    sourceOfMoney = source,
                    note = note,
                    dateStr = date,
                    timeStr = time
                )
                if (result.allowed) {
                    activeSheetType = null
                    presetSheetCategory = null
                    editingTransaction = null
                }
            }
        )
    }

    insufficientBalanceAlert?.let { validation ->
        InsufficientBalanceDialog(
            currency = profile?.currencySymbol ?: "₹",
            validation = validation,
            onAddBalance = {
                viewModel.dismissInsufficientBalanceAlert()
                editingTransaction = null
                presetSheetCategory = null
                activeSheetType = TransactionType.INCOME
            },
            onDismiss = { viewModel.dismissInsufficientBalanceAlert() }
        )
    }

    if (balanceExhaustedAlert) {
        BalanceExhaustedDialog(
            onDismiss = { viewModel.dismissBalanceExhaustedAlert() }
        )
    }
}
