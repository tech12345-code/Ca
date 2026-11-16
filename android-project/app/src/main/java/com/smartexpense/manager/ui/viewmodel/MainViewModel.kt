package com.smartexpense.manager.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.smartexpense.manager.data.remote.FirestoreSyncService
import com.smartexpense.manager.data.repository.ExpenseRepository
import com.smartexpense.manager.data.repository.SplitExpenseRepository
import com.smartexpense.manager.domain.calculator.BalanceCalculator
import com.smartexpense.manager.domain.calculator.ParsedUpiUri
import com.smartexpense.manager.domain.calculator.SettlementAction
import com.smartexpense.manager.domain.calculator.SplitExpenseCalculator
import com.smartexpense.manager.domain.model.AutoLockTimeout
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.BalanceValidationResult
import com.smartexpense.manager.domain.model.BiometricCapability
import com.smartexpense.manager.domain.model.CompatibleUpiApp
import com.smartexpense.manager.domain.model.DateFilterPreset
import com.smartexpense.manager.domain.model.EntrySource
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.ExtractedReceiptDraft
import com.smartexpense.manager.domain.model.FilteredLedgerState
import com.smartexpense.manager.domain.model.GroupBalanceSummary
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.GroupType
import com.smartexpense.manager.domain.model.InvitationStatus
import com.smartexpense.manager.domain.model.InviteMethod
import com.smartexpense.manager.domain.model.NotificationPreferences
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.domain.model.SecuritySettings
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementPaymentMethod
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SimplifiedDebt
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SplitType
import com.smartexpense.manager.domain.model.SyncStatus
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionFilter
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.model.UserProfile
import com.smartexpense.manager.domain.ocr.ReceiptOcrParser
import com.smartexpense.manager.domain.pdf.PdfStatementGenerator
import com.smartexpense.manager.domain.qr.UpiQrGenerator
import com.smartexpense.manager.domain.security.AppLockManager
import com.smartexpense.manager.domain.upi.UpiAppDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

data class UiFeedback(
    val message: String,
    val isError: Boolean = false
)

data class SplitWorkspaceSummary(
    val totalGroupExpenses: Double = 0.0,
    val myOutstandingBalance: Double = 0.0,
    val amountOthersOweMe: Double = 0.0,
    val pendingInvitationsCount: Int = 0,
    val pendingSettlementsCount: Int = 0
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val repository: ExpenseRepository,
    private val splitRepository: SplitExpenseRepository,
    private val firestoreSync: FirestoreSyncService,
    private val calculator: BalanceCalculator,
    private val splitCalculator: SplitExpenseCalculator,
    private val upiQrGenerator: UpiQrGenerator,
    private val ocrParser: ReceiptOcrParser,
    private val upiDetector: UpiAppDetector,
    private val pdfGenerator: PdfStatementGenerator,
    private val appLockManager: AppLockManager
) : ViewModel() {

    private val _currentUid = MutableStateFlow(auth.currentUser?.uid)
    val currentUid: StateFlow<String?> = _currentUid.asStateFlow()

    val securitySettings: StateFlow<SecuritySettings> = appLockManager.settings
    val isAppUnlocked: StateFlow<Boolean> = appLockManager.isUnlocked

    private val _compatibleUpiApps = MutableStateFlow(upiDetector.detectCompatibleUpiApps())
    val compatibleUpiApps: StateFlow<List<CompatibleUpiApp>> = _compatibleUpiApps.asStateFlow()

    private val _transactionFilter = MutableStateFlow(TransactionFilter())
    val transactionFilter: StateFlow<TransactionFilter> = _transactionFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val userProfile: StateFlow<UserProfile?> = _currentUid.flatMapLatest { uid ->
        if (uid == null) flowOf(null) else repository.observeProfile(uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val balanceSummary: StateFlow<BalanceSummary> = _currentUid.flatMapLatest { uid ->
        if (uid == null) {
            flowOf(BalanceSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()))
        } else {
            repository.observeBalanceSummary(uid)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        BalanceSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    )

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val filteredLedgerState: StateFlow<FilteredLedgerState> = combine(
        _currentUid,
        _transactionFilter.debounce(120)
    ) { uid, filter -> uid to filter }
        .flatMapLatest { (uid, filter) ->
            if (uid == null) flowOf(FilteredLedgerState())
            else repository.observeFilteredLedger(uid, filter)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilteredLedgerState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val availablePaymentMethods: StateFlow<List<String>> = _currentUid.flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList()) else repository.observePaymentMethods(uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshCompatibleUpiApps() {
        _compatibleUpiApps.value = upiDetector.detectCompatibleUpiApps()
    }

    fun buildTargetedUpiPaymentIntent(upiUri: String, packageName: String? = null): Intent =
        upiDetector.buildTargetedUpiPaymentIntent(upiUri, packageName)

    fun getBiometricCapability(): BiometricCapability = appLockManager.getBiometricCapability()

    fun configureFirstTimeSecurity(
        method: SecurityMethod,
        pin: String = "",
        confirmPin: String = "",
        enableBiometricQuickUnlock: Boolean = false
    ): Result<Unit> {
        val result = appLockManager.configureFirstTimeSecurity(
            method = method,
            pin = pin,
            confirmPin = confirmPin,
            enableBiometricQuickUnlock = enableBiometricQuickUnlock
        )
        result.onSuccess {
            val label = when (method) {
                SecurityMethod.SEM_PIN -> "SEM-PIN enabled securely on this device."
                SecurityMethod.DEVICE_LOCK -> "Phone screen lock & biometric protection enabled."
                SecurityMethod.NONE -> "App Lock skipped. You can enable SEM-PIN anytime in Settings."
            }
            _feedback.value = UiFeedback(label)
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Unable to save security settings.", isError = true)
        }
        return result
    }

    fun verifySemPin(pin: String): Result<Unit> = appLockManager.verifySemPin(pin)

    fun changeSemPin(currentPin: String, newPin: String, confirmNewPin: String): Result<Unit> {
        val res = appLockManager.changeSemPin(currentPin, newPin, confirmNewPin)
        res.onSuccess {
            _feedback.value = UiFeedback("SEM-PIN updated securely.")
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Failed to update SEM-PIN.", isError = true)
        }
        return res
    }

    fun setupOrReplaceSemPinFromSettings(newPin: String, confirmNewPin: String): Result<Unit> {
        val res = appLockManager.configureFirstTimeSecurity(
            method = SecurityMethod.SEM_PIN,
            pin = newPin,
            confirmPin = confirmNewPin,
            enableBiometricQuickUnlock = securitySettings.value.fingerprintQuickUnlockEnabled
        )
        res.onSuccess {
            _feedback.value = UiFeedback("SEM-PIN set as primary App Lock.")
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Could not save SEM-PIN.", isError = true)
        }
        return res
    }

    fun resetForgottenPinWithGoogleReauth(
        idToken: String,
        newPin: String,
        confirmNewPin: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onResult(Result.failure(IllegalStateException("No signed-in Firebase account found.")))
            return
        }
        viewModelScope.launch {
            val reauthResult = runCatching {
                val cred = GoogleAuthProvider.getCredential(idToken, null)
                currentUser.reauthenticate(cred).await()
            }
            if (reauthResult.isFailure) {
                onResult(
                    Result.failure(
                        IllegalStateException(
                            reauthResult.exceptionOrNull()?.message ?: "Google account verification failed."
                        )
                    )
                )
                return@launch
            }
            val resetRes = appLockManager.resetForgottenPinAfterIdentityVerification(
                identityVerified = true,
                newPin = newPin,
                confirmNewPin = confirmNewPin
            )
            resetRes.onSuccess {
                _feedback.value = UiFeedback("Identity verified. New SEM-PIN saved.")
            }
            onResult(resetRes)
        }
    }

    fun resetForgottenPinAfterDeviceAuth(newPin: String, confirmNewPin: String): Result<Unit> {
        val res = appLockManager.resetForgottenPinAfterIdentityVerification(
            identityVerified = true,
            newPin = newPin,
            confirmNewPin = confirmNewPin
        )
        res.onSuccess {
            _feedback.value = UiFeedback("Device lock verified. New SEM-PIN saved.")
        }
        return res
    }

    fun updateSecurityMethod(method: SecurityMethod): Result<Unit> {
        val res = appLockManager.updateSecurityMethod(method)
        res.onSuccess {
            _feedback.value = UiFeedback("Security method switched to ${method.label}.")
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Unable to switch security method.", isError = true)
        }
        return res
    }

    fun setAppLockEnabled(enabled: Boolean): Result<Unit> {
        val res = appLockManager.setAppLockEnabled(enabled)
        res.onSuccess {
            _feedback.value = UiFeedback(if (enabled) "SEM App Lock enabled." else "SEM App Lock disabled.")
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Could not toggle App Lock.", isError = true)
        }
        return res
    }

    fun setFingerprintQuickUnlock(enabled: Boolean) {
        val res = appLockManager.setFingerprintQuickUnlock(enabled)
        res.onSuccess {
            _feedback.value = UiFeedback(
                if (enabled) "Fingerprint quick unlock enabled." else "Fingerprint quick unlock disabled."
            )
        }.onFailure { err ->
            _feedback.value = UiFeedback(err.message ?: "Biometrics unavailable.", isError = true)
        }
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        appLockManager.setAutoLockTimeout(timeout)
        _feedback.value = UiFeedback("Auto-lock timeout set to ${timeout.label}.")
    }

    fun setLockOnAppClose(enabled: Boolean) {
        appLockManager.setLockOnAppClose(enabled)
        _feedback.value = UiFeedback(
            if (enabled) "App will lock immediately on close." else "Lock on close disabled."
        )
    }

    fun launchSystemBiometricUnlock(
        activity: FragmentActivity,
        title: String = "Unlock Smart Expense Manager",
        subtitle: String = "Confirm your fingerprint or phone screen lock",
        allowDeviceCredential: Boolean = true,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        appLockManager.launchSystemBiometricPrompt(
            activity = activity,
            title = title,
            subtitle = subtitle,
            allowDeviceCredential = allowDeviceCredential,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun lockAppNow() {
        appLockManager.lockNow()
    }

    fun updateSearchQuery(query: String) {
        _transactionFilter.update { it.copy(searchQuery = query) }
    }

    fun setDatePreset(preset: DateFilterPreset) {
        val today = LocalDate.now()
        val (start, end) = when (preset) {
            DateFilterPreset.ALL -> null to null
            DateFilterPreset.TODAY -> today.toString() to today.toString()
            DateFilterPreset.LAST_7_DAYS -> today.minusDays(6).toString() to today.toString()
            DateFilterPreset.THIS_MONTH -> today.withDayOfMonth(1).toString() to today.toString()
            DateFilterPreset.LAST_MONTH -> {
                val prev = YearMonth.now().minusMonths(1)
                prev.atDay(1).toString() to prev.atEndOfMonth().toString()
            }
            DateFilterPreset.CUSTOM -> _transactionFilter.value.startDate to _transactionFilter.value.endDate
        }
        _transactionFilter.update {
            it.copy(datePreset = preset, startDate = start, endDate = end)
        }
    }

    fun setCustomDateRange(startDate: String?, endDate: String?) {
        val safeStart = startDate?.takeIf { it.isNotBlank() }
        val safeEnd = endDate?.takeIf { it.isNotBlank() }
        val normalizedEnd = if (safeStart != null && safeEnd != null && safeStart > safeEnd) safeStart else safeEnd
        _transactionFilter.update {
            it.copy(
                datePreset = if (safeStart == null && normalizedEnd == null) DateFilterPreset.ALL else DateFilterPreset.CUSTOM,
                startDate = safeStart,
                endDate = normalizedEnd
            )
        }
    }

    fun toggleCategoryFilter(category: ExpenseCategory) {
        _transactionFilter.update { current ->
            val next = current.selectedCategories.toMutableSet()
            if (!next.add(category)) next.remove(category)
            current.copy(selectedCategories = next)
        }
    }

    fun clearCategoriesFilter() {
        _transactionFilter.update { it.copy(selectedCategories = emptySet()) }
    }

    fun togglePaymentMethodFilter(method: String) {
        _transactionFilter.update { current ->
            val next = current.selectedPaymentMethods.toMutableSet()
            if (!next.add(method)) next.remove(method)
            current.copy(selectedPaymentMethods = next)
        }
    }

    fun clearPaymentMethodsFilter() {
        _transactionFilter.update { it.copy(selectedPaymentMethods = emptySet()) }
    }

    fun setTypeFilter(type: TransactionType?) {
        _transactionFilter.update { it.copy(transactionType = type) }
    }

    fun clearAllFilters() {
        _transactionFilter.value = TransactionFilter()
    }

    private val _receiptDraft = MutableStateFlow<ExtractedReceiptDraft?>(null)
    val receiptDraft: StateFlow<ExtractedReceiptDraft?> = _receiptDraft.asStateFlow()

    private val _isScanningReceipt = MutableStateFlow(false)
    val isScanningReceipt: StateFlow<Boolean> = _isScanningReceipt.asStateFlow()

    private val _feedback = MutableStateFlow<UiFeedback?>(null)
    val feedback: StateFlow<UiFeedback?> = _feedback.asStateFlow()

    private val _insufficientBalanceAlert = MutableStateFlow<BalanceValidationResult?>(null)
    val insufficientBalanceAlert: StateFlow<BalanceValidationResult?> = _insufficientBalanceAlert.asStateFlow()

    private val _balanceExhaustedAlert = MutableStateFlow(false)
    val balanceExhaustedAlert: StateFlow<Boolean> = _balanceExhaustedAlert.asStateFlow()

    val splitGroups: StateFlow<List<SplitGroup>> = splitRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupMembers: StateFlow<List<GroupMember>> = splitRepository.observeMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupInvitations: StateFlow<List<GroupInvitation>> = splitRepository.observeInvitations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupExpenses: StateFlow<List<GroupExpense>> = splitRepository.observeGroupExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settlements: StateFlow<List<Settlement>> = splitRepository.observeSettlements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val splitWorkspaceSummary: StateFlow<SplitWorkspaceSummary> = combine(
        _currentUid,
        splitGroups,
        groupMembers,
        groupExpenses,
        settlements,
        groupInvitations
    ) { values ->
        val uid = values[0] as String?
        @Suppress("UNCHECKED_CAST")
        val groups = values[1] as List<SplitGroup>
        @Suppress("UNCHECKED_CAST")
        val members = values[2] as List<GroupMember>
        @Suppress("UNCHECKED_CAST")
        val expenses = values[3] as List<GroupExpense>
        @Suppress("UNCHECKED_CAST")
        val allSettlements = values[4] as List<Settlement>
        @Suppress("UNCHECKED_CAST")
        val invites = values[5] as List<GroupInvitation>

        if (uid == null) return@combine SplitWorkspaceSummary()

        var totalGroup = 0.0
        var iOwe = 0.0
        var owedToMe = 0.0
        for (grp in groups) {
            val gMembers = members.filter { it.groupId == grp.id }
            val gExpenses = expenses.filter { it.groupId == grp.id }
            val gSettlements = allSettlements.filter { it.groupId == grp.id }
            val summary = splitCalculator.computeBalances(gMembers, gExpenses, gSettlements)
            totalGroup += summary.totalGroupExpenses
            val myBal = summary.memberBalances.firstOrNull { it.uid == uid }?.netBalance ?: 0.0
            if (myBal < -0.005) iOwe += -myBal
            if (myBal > 0.005) owedToMe += myBal
        }
        val pendingInv = invites.count { it.status == InvitationStatus.PENDING && it.inviterUid != uid }
        val pendingSett = allSettlements.count {
            it.status != SettlementStatus.COMPLETED &&
                it.status != SettlementStatus.REJECTED &&
                it.status != SettlementStatus.FAILED
        }
        SplitWorkspaceSummary(
            totalGroupExpenses = splitCalculator.round2(totalGroup),
            myOutstandingBalance = splitCalculator.round2(iOwe),
            amountOthersOweMe = splitCalculator.round2(owedToMe),
            pendingInvitationsCount = pendingInv,
            pendingSettlementsCount = pendingSett
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SplitWorkspaceSummary())

    fun clearFeedback() {
        _feedback.value = null
    }

    fun dismissInsufficientBalanceAlert() {
        _insufficientBalanceAlert.value = null
    }

    fun dismissBalanceExhaustedAlert() {
        _balanceExhaustedAlert.value = false
    }

    fun validateTransactionSave(
        existingId: String?,
        type: TransactionType,
        newAmount: Double
    ): BalanceValidationResult {
        val existingTx = existingId?.let { id ->
            balanceSummary.value.ledger.firstOrNull { it.transaction.id == id }?.transaction
        }
        return calculator.validateTransactionBalance(
            type = type,
            newAmount = newAmount,
            currentAvailableBalance = balanceSummary.value.availableBalance,
            existingTransaction = existingTx
        )
    }

    fun signInWithGoogleIdToken(idToken: String, displayName: String?, email: String?) {
        viewModelScope.launch {
            runCatching {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(credential).await()
                val user = authResult.user ?: error("Authentication failed")
                _currentUid.value = user.uid
                val appCode = splitCalculator.generateAppUserCode(user.uid)
                val remote = firestoreSync.fetchUserProfile(user.uid)
                if (remote != null) {
                    val enriched = if (remote.appUserCode.isBlank()) remote.copy(appUserCode = appCode) else remote
                    repository.saveProfile(enriched)
                    repository.restoreFromCloud(user.uid)
                    runCatching {
                        firestoreSync.upsertUserDirectory(
                            uid = enriched.uid,
                            appUserCode = enriched.appUserCode,
                            displayName = enriched.displayName,
                            upiId = enriched.upiId,
                            upiVerified = enriched.upiVerified
                        )
                    }
                    splitRepository.syncSplitWorkspace(enriched.uid, enriched.email)
                } else {
                    val fresh = UserProfile(
                        uid = user.uid,
                        displayName = displayName ?: user.displayName ?: "User",
                        email = email ?: user.email ?: "",
                        appUserCode = appCode,
                        openingBalance = 0.0,
                        monthlyBudget = 0.0,
                        setupCompleted = false
                    )
                    repository.saveProfile(fresh)
                    runCatching {
                        firestoreSync.upsertUserDirectory(
                            uid = fresh.uid,
                            appUserCode = fresh.appUserCode,
                            displayName = fresh.displayName,
                            upiId = "",
                            upiVerified = false
                        )
                    }
                }
                registerFcmToken(user.uid)
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Google Sign-In failed", isError = true)
            }
        }
    }

    fun completeFirstTimeSetup(monthlyBudgetInput: Double) {
        val uid = _currentUid.value ?: return
        if (monthlyBudgetInput < 0.0 || monthlyBudgetInput.isNaN()) {
            _feedback.value = UiFeedback("Enter a valid budget amount", isError = true)
            return
        }
        viewModelScope.launch {
            val appCode = splitCalculator.generateAppUserCode(uid)
            val current = userProfile.value ?: UserProfile(
                uid = uid,
                displayName = auth.currentUser?.displayName ?: "User",
                email = auth.currentUser?.email ?: "",
                appUserCode = appCode,
                openingBalance = monthlyBudgetInput,
                monthlyBudget = monthlyBudgetInput,
                setupCompleted = true
            )
            val updated = current.copy(
                appUserCode = current.appUserCode.ifBlank { appCode },
                openingBalance = monthlyBudgetInput,
                monthlyBudget = monthlyBudgetInput,
                setupCompleted = true
            )
            repository.saveProfile(updated)
            runCatching {
                firestoreSync.upsertUserDirectory(
                    uid = updated.uid,
                    appUserCode = updated.appUserCode,
                    displayName = updated.displayName,
                    upiId = updated.upiId,
                    upiVerified = updated.upiVerified
                )
            }
            _feedback.value = UiFeedback("Budget and opening balance saved.")
        }
    }

    fun updateMonthlyBudget(newBudget: Double) {
        val current = userProfile.value ?: return
        if (newBudget < 0.0 || newBudget.isNaN()) return
        viewModelScope.launch {
            repository.saveProfile(current.copy(monthlyBudget = newBudget))
            _feedback.value = UiFeedback("Monthly budget updated.")
        }
    }

    fun updateNotificationPreferences(prefs: NotificationPreferences) {
        val current = userProfile.value ?: return
        viewModelScope.launch {
            repository.saveProfile(current.copy(preferences = prefs))
            _feedback.value = UiFeedback("Notification preferences saved.")
        }
    }

    fun updateMyUpiId(rawUpiId: String) {
        val current = userProfile.value ?: return
        val res = splitCalculator.validateUpiId(rawUpiId)
        if (res.isFailure) {
            _feedback.value = UiFeedback(res.exceptionOrNull()?.message ?: "Invalid UPI ID", isError = true)
            return
        }
        val cleanVpa = res.getOrThrow()
        val appCode = current.appUserCode.ifBlank { splitCalculator.generateAppUserCode(current.uid) }
        val updated = current.copy(appUserCode = appCode, upiId = cleanVpa, upiVerified = true)
        viewModelScope.launch {
            repository.saveProfile(updated)
            runCatching {
                firestoreSync.upsertUserDirectory(
                    uid = updated.uid,
                    appUserCode = updated.appUserCode,
                    displayName = updated.displayName,
                    upiId = updated.upiId,
                    upiVerified = true
                )
            }
            _feedback.value = UiFeedback("UPI ID $cleanVpa saved & format verified.")
        }
    }

    fun refreshSplitWorkspace() {
        val profile = userProfile.value ?: return
        viewModelScope.launch {
            splitRepository.syncSplitWorkspace(profile.uid, profile.email).onSuccess {
                _feedback.value = UiFeedback("Split workspace synced.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Offline mode — local split ledger active.", isError = true)
            }
        }
    }

    fun createSplitGroup(name: String, groupType: GroupType, dueDate: String) {
        val profile = userProfile.value ?: return
        if (name.isBlank()) {
            _feedback.value = UiFeedback("Enter a group name.", isError = true)
            return
        }
        viewModelScope.launch {
            splitRepository.createGroup(name, groupType, dueDate, profile).onSuccess { grp ->
                _feedback.value = UiFeedback("Group '${grp.name}' created (Code: ${grp.joinCode}).")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Could not create group.", isError = true)
            }
        }
    }

    fun joinGroupByCode(joinCode: String) {
        val profile = userProfile.value ?: return
        if (joinCode.isBlank()) {
            _feedback.value = UiFeedback("Enter a valid group join code.", isError = true)
            return
        }
        viewModelScope.launch {
            splitRepository.joinGroupByCode(joinCode, profile).onSuccess {
                _feedback.value = UiFeedback("Joined group successfully.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Could not join group.", isError = true)
            }
        }
    }

    fun leaveSplitGroup(group: SplitGroup) {
        val uid = _currentUid.value ?: return
        val gMembers = groupMembers.value.filter { it.groupId == group.id }
        val gExpenses = groupExpenses.value.filter { it.groupId == group.id }
        val gSettlements = settlements.value.filter { it.groupId == group.id }
        viewModelScope.launch {
            splitRepository.leaveGroup(group, gMembers, gExpenses, gSettlements, uid).onSuccess {
                _feedback.value = UiFeedback("Left '${group.name}'.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Cannot leave group right now.", isError = true)
            }
        }
    }

    fun sendGroupInvitation(group: SplitGroup, method: InviteMethod, targetInput: String) {
        val profile = userProfile.value ?: return
        viewModelScope.launch {
            splitRepository.sendInvitation(group, profile, method, targetInput).onSuccess { inv ->
                _feedback.value = UiFeedback("Invitation created (Token: ${inv.inviteToken}).")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Failed to create invitation.", isError = true)
            }
        }
    }

    fun respondToGroupInvitation(inv: GroupInvitation, accept: Boolean) {
        val profile = userProfile.value ?: return
        viewModelScope.launch {
            splitRepository.respondToInvitation(inv, accept, profile).onSuccess {
                _feedback.value = UiFeedback(if (accept) "Joined ${inv.groupName}." else "Invitation declined.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Could not update invitation.", isError = true)
            }
        }
    }

    fun computeGroupSummary(groupId: String): GroupBalanceSummary {
        val gMembers = groupMembers.value.filter { it.groupId == groupId && it.status == GroupMemberStatus.ACTIVE }
        val gExpenses = groupExpenses.value.filter { it.groupId == groupId }
        val gSettlements = settlements.value.filter { it.groupId == groupId }
        return splitCalculator.computeBalances(gMembers, gExpenses, gSettlements)
    }

    fun saveGroupExpense(
        existingId: String? = null,
        group: SplitGroup,
        paidByUid: String,
        paidByName: String,
        amount: Double,
        description: String,
        category: ExpenseCategory,
        customCategoryDetail: String,
        dateStr: String,
        timeStr: String,
        splitType: SplitType,
        participantUids: List<String>,
        customInputs: Map<String, Double>,
        receiptReference: String,
        note: String
    ): Boolean {
        if (description.isBlank()) {
            _feedback.value = UiFeedback("Enter a group expense description.", isError = true)
            return false
        }
        val cleanCustomDetail =
            if (category == ExpenseCategory.Other) customCategoryDetail.trim().take(120) else ""
        if (category == ExpenseCategory.Other && cleanCustomDetail.isBlank()) {
            _feedback.value = UiFeedback("Enter details for 'Where have you spent this money?'.", isError = true)
            return false
        }
        val shareResult = splitCalculator.calculateSplitShares(
            totalAmount = amount,
            splitType = splitType,
            participantUids = participantUids,
            customInputs = customInputs
        )
        if (shareResult.isFailure) {
            _feedback.value = UiFeedback(shareResult.exceptionOrNull()?.message ?: "Invalid split amounts.", isError = true)
            return false
        }
        val shares = shareResult.getOrThrow()
        val now = System.currentTimeMillis()
        val finalDate = dateStr.ifBlank { LocalDate.now().toString() }
        val finalTime = timeStr.ifBlank { "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute) }
        val expense = GroupExpense(
            id = existingId ?: "gexp_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            groupId = group.id,
            memberUids = group.memberUids,
            paidByUid = paidByUid,
            paidByName = paidByName,
            amount = splitCalculator.round2(amount),
            description = description.trim(),
            category = category,
            customCategoryDetail = cleanCustomDetail,
            transactionDate = finalDate,
            transactionTime = finalTime,
            timestampMillis = calculator.toTimestampMillis(finalDate, finalTime),
            splitType = splitType,
            participantUids = participantUids,
            shares = shares,
            receiptReference = receiptReference.trim(),
            note = note.trim(),
            deleted = false,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        viewModelScope.launch {
            splitRepository.saveGroupExpense(expense).onSuccess {
                _receiptDraft.value = null
                _feedback.value = UiFeedback(
                    if (existingId != null) "Group expense updated." else "Group expense added & shares recalculated."
                )
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Could not save group expense.", isError = true)
            }
        }
        return true
    }

    fun deleteGroupExpense(expense: GroupExpense) {
        viewModelScope.launch {
            splitRepository.deleteGroupExpense(expense).onSuccess {
                _feedback.value = UiFeedback("Group expense deleted & balances updated.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Failed to delete group expense.", isError = true)
            }
        }
    }

    fun createSettlementFromDebt(
        group: SplitGroup,
        debt: SimplifiedDebt,
        dueDate: String,
        method: SettlementPaymentMethod = SettlementPaymentMethod.UPI,
        onCreated: (Settlement) -> Unit = {}
    ) {
        viewModelScope.launch {
            splitRepository.createSettlement(
                group = group,
                payerUid = debt.fromUid,
                payerName = debt.fromName,
                receiverUid = debt.toUid,
                receiverName = debt.toName,
                receiverUpiId = debt.toUpiId,
                amount = debt.amount,
                dueDate = dueDate.ifBlank { group.settlementDueDate },
                method = method
            ).onSuccess { created ->
                _feedback.value = UiFeedback("Settlement request prepared for ${created.receiverName}.")
                onCreated(created)
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Could not create settlement.", isError = true)
            }
        }
    }

    fun performSettlementAction(
        settlement: Settlement,
        action: SettlementAction,
        paymentApp: String = settlement.paymentApp,
        paymentReference: String = "",
        method: SettlementPaymentMethod = settlement.paymentMethod,
        recordInPersonalLedger: Boolean = false
    ) {
        val uid = _currentUid.value ?: return
        viewModelScope.launch {
            splitRepository.transitionSettlement(
                settlement = settlement,
                allSettlements = settlements.value,
                actorUid = uid,
                action = action,
                paymentApp = paymentApp,
                paymentReference = paymentReference,
                method = method
            ).onSuccess { updated ->
                if (action == SettlementAction.RECEIVER_CONFIRM && recordInPersonalLedger) {
                    val isIncome = updated.receiverUid == uid
                    saveTransaction(
                        type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                        amount = updated.amount,
                        description = "Split settlement (${updated.groupName})",
                        merchant = if (isIncome) updated.payerName else updated.receiverName,
                        category = if (isIncome) ExpenseCategory.Income else ExpenseCategory.Other,
                        customCategoryDetail = if (isIncome) "" else "Group settlement: ${updated.groupName}",
                        paymentMethod = updated.paymentApp.ifBlank { updated.paymentMethod.name },
                        sourceOfMoney = if (isIncome) updated.payerName else "",
                        note = "Ref: ${updated.paymentReference}",
                        dateStr = LocalDate.now().toString(),
                        timeStr = "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute),
                        referenceId = updated.paymentReference,
                        entrySource = EntrySource.UPI_CONFIRMED
                    )
                }
                _feedback.value = UiFeedback("Settlement status: ${updated.status.label}")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Settlement update blocked.", isError = true)
            }
        }
    }

    fun buildSettlementUpiUri(settlement: Settlement): String {
        if (settlement.receiverUpiId.isBlank()) return ""
        return splitCalculator.buildUpiPaymentUri(
            upiId = settlement.receiverUpiId,
            payeeName = settlement.receiverName,
            amount = settlement.amount,
            note = settlement.note.ifBlank { "${settlement.groupName} settlement" },
            transactionRef = "SEM${settlement.id.takeLast(8).uppercase()}"
        )
    }

    fun generateQrBitmap(uri: String): ImageBitmap? = upiQrGenerator.generateQrBitmap(uri)

    fun parseScannedQrUri(rawUri: String): ParsedUpiUri = splitCalculator.parseUpiPaymentUri(rawUri)

    fun saveTransaction(
        existingId: String? = null,
        type: TransactionType,
        amount: Double,
        description: String,
        merchant: String,
        category: ExpenseCategory,
        customCategoryDetail: String = "",
        paymentMethod: String,
        sourceOfMoney: String,
        note: String,
        dateStr: String,
        timeStr: String,
        referenceId: String = "",
        invoiceNumber: String = "",
        entrySource: EntrySource = EntrySource.MANUAL
    ): BalanceValidationResult {
        val uid = _currentUid.value ?: return BalanceValidationResult(
            allowed = false,
            currentAvailableBalance = 0.0,
            errorTitle = "Not Signed In",
            errorMessage = "Please sign in to record transactions."
        )

        val validation = validateTransactionSave(existingId, type, amount)
        if (!validation.allowed) {
            _insufficientBalanceAlert.value = validation
            return validation
        }

        val now = System.currentTimeMillis()
        val resolvedCategory = if (type == TransactionType.INCOME) ExpenseCategory.Income else category
        val resolvedCustomDetail =
            if (type == TransactionType.EXPENSE && resolvedCategory == ExpenseCategory.Other) {
                customCategoryDetail.trim().take(120)
            } else {
                ""
            }
        val tx = Transaction(
            id = existingId ?: repository.newTransactionId(),
            uid = uid,
            type = type,
            amount = amount,
            description = description.trim(),
            merchant = merchant.trim(),
            category = resolvedCategory,
            customCategoryDetail = resolvedCustomDetail,
            paymentMethod = paymentMethod.trim(),
            sourceOfMoney = sourceOfMoney.trim(),
            note = note.trim(),
            transactionDate = dateStr.ifBlank { LocalDate.now().toString() },
            transactionTime = timeStr.ifBlank { "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute) },
            timestampMillis = calculator.toTimestampMillis(dateStr, timeStr),
            referenceId = referenceId.trim(),
            invoiceNumber = invoiceNumber.trim(),
            entrySource = entrySource,
            createdAtMillis = now,
            updatedAtMillis = now
        )

        viewModelScope.launch {
            repository.saveTransaction(tx).onSuccess {
                _receiptDraft.value = null
                if (type == TransactionType.EXPENSE && validation.willExhaustBalance) {
                    _balanceExhaustedAlert.value = true
                }
                _feedback.value = UiFeedback(
                    if (existingId != null) "Transaction updated & balance recalculated."
                    else "Transaction saved & balance recalculated."
                )
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Failed to save transaction", isError = true)
            }
        }
        return validation
    }

    fun deleteTransaction(txId: String) {
        val uid = _currentUid.value ?: return
        viewModelScope.launch {
            repository.deleteTransaction(uid, txId)
            _feedback.value = UiFeedback("Transaction deleted & balance updated.")
        }
    }

    fun scanReceiptUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isScanningReceipt.value = true
            runCatching {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val existing = balanceSummary.value.ledger.map { it.transaction }
                if (mime.contains("pdf")) {
                    ocrParser.extractFromPdfUri(uri, existing)
                } else {
                    ocrParser.extractFromImageUri(uri, existing)
                }
            }.onSuccess { draft ->
                _receiptDraft.value = draft
            }.onFailure { err ->
                _feedback.value = UiFeedback("OCR scan failed: ${err.message}", isError = true)
            }
            _isScanningReceipt.value = false
        }
    }

    fun clearReceiptDraft() {
        _receiptDraft.value = null
    }

    fun backupNow() {
        val uid = _currentUid.value ?: return
        val email = userProfile.value?.email.orEmpty()
        viewModelScope.launch {
            repository.syncPendingToCloud(uid).onSuccess { count ->
                runCatching { splitRepository.syncSplitWorkspace(uid, email) }
                _feedback.value = UiFeedback("Cloud backup & split workspace synced ($count records).")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Backup failed", isError = true)
            }
        }
    }

    fun restoreFromCloud() {
        val uid = _currentUid.value ?: return
        val email = userProfile.value?.email.orEmpty()
        viewModelScope.launch {
            repository.restoreFromCloud(uid).onSuccess { count ->
                runCatching { splitRepository.syncSplitWorkspace(uid, email) }
                _feedback.value = UiFeedback("Restored $count transactions & split groups from Firestore.")
            }.onFailure { err ->
                _feedback.value = UiFeedback(err.message ?: "Restore failed", isError = true)
            }
        }
    }

    fun exportPdfStatement(startDate: String, endDate: String): File? {
        val profile = userProfile.value ?: return null
        val summary = balanceSummary.value
        val filtered = summary.ledger.filter {
            it.transaction.transactionDate >= startDate && it.transaction.transactionDate <= endDate
        }
        return pdfGenerator.generateStatement(
            userName = profile.displayName,
            currencySymbol = profile.currencySymbol,
            startDate = startDate,
            endDate = endDate,
            summary = summary,
            filteredEntries = filtered
        )
    }

    fun signOut() {
        auth.signOut()
        appLockManager.clearSecurityOnSignOut()
        _currentUid.value = null
    }

    private fun registerFcmToken(uid: String) {
        viewModelScope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                val tokenId = token.take(24).replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                firestoreSync.saveFcmToken(uid, tokenId, token)
            }
        }
    }
}
