package com.smartexpense.manager.domain.model

enum class TransactionType {
    EXPENSE,
    INCOME
}

enum class ExpenseCategory(val displayName: String) {
    Food("Food"),
    Travel("Travel"),
    Groceries("Groceries"),
    Shopping("Shopping"),
    Education("Education"),
    Bills("Bills"),
    Entertainment("Entertainment"),
    Health("Health"),
    Other("Other"),
    Income("Income");

    companion object {
        val expenseCategories: List<ExpenseCategory> = listOf(
            Food, Travel, Groceries, Shopping, Education, Bills, Entertainment, Health, Other
        )
    }
}

enum class EntrySource {
    MANUAL,
    RECEIPT_OCR,
    UPI_CONFIRMED,
    CLOUD_RESTORE
}

enum class SyncStatus {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DELETE
}

data class Transaction(
    val id: String,
    val uid: String,
    val type: TransactionType,
    val amount: Double,
    val description: String,
    val merchant: String = "",
    val category: ExpenseCategory,
    val customCategoryDetail: String = "",
    val paymentMethod: String = "UPI",
    val sourceOfMoney: String = "",
    val note: String = "",
    val transactionDate: String, // YYYY-MM-DD
    val transactionTime: String, // HH:mm
    val timestampMillis: Long,
    val referenceId: String = "",
    val invoiceNumber: String = "",
    val entrySource: EntrySource = EntrySource.MANUAL,
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun categoryLabel(): String =
        if (category == ExpenseCategory.Other && customCategoryDetail.isNotBlank()) {
            "Other — ${customCategoryDetail.trim()}"
        } else {
            category.displayName
        }
}

data class LedgerEntry(
    val transaction: Transaction,
    val runningBalanceAfter: Double
)

enum class BudgetStatusColor {
    GREEN,
    YELLOW,
    RED
}

data class BalanceValidationResult(
    val allowed: Boolean,
    val currentAvailableBalance: Double,
    val shortfallAmount: Double = 0.0,
    val willExhaustBalance: Boolean = false,
    val errorTitle: String? = null,
    val errorMessage: String? = null
)

data class BalanceSummary(
    val openingBalance: Double,
    val totalIncome: Double,
    val totalExpenses: Double,
    val availableBalance: Double,
    val monthlyBudget: Double,
    val monthlySpent: Double,
    val remainingBudget: Double,
    val budgetUsagePercent: Double,
    val ledger: List<LedgerEntry>,
    val budgetStatusColor: BudgetStatusColor = BudgetStatusColor.GREEN,
    val isBudgetExceeded: Boolean = false,
    val isBalanceExhausted: Boolean = false
)

data class ExtractedReceiptDraft(
    val merchantName: String = "",
    val amount: String = "",
    val transactionDate: String = "",
    val transactionTime: String = "",
    val paymentMethod: String = "",
    val referenceId: String = "",
    val invoiceNumber: String = "",
    val rawText: String = "",
    val missingFields: List<String> = emptyList(),
    val isPossibleDuplicate: Boolean = false,
    val duplicateReason: String? = null
)

data class CompatibleUpiApp(
    val appName: String,
    val packageName: String
)

enum class SecurityMethod(val label: String, val subtitle: String) {
    SEM_PIN(
        label = "SEM-PIN",
        subtitle = "Personal 4–6 digit numeric PIN stored using Android Keystore AES256-GCM encryption."
    ),
    DEVICE_LOCK(
        label = "Phone Screen Lock",
        subtitle = "Official Android BiometricPrompt supporting Fingerprint, Face, PIN, Pattern, or Password."
    ),
    NONE(
        label = "No App Lock",
        subtitle = "Opens Smart Expense Manager directly without additional lock-screen verification."
    )
}

enum class AutoLockTimeout(val seconds: Int, val label: String) {
    IMMEDIATE(0, "Immediately"),
    SECONDS_30(30, "After 30 seconds"),
    MINUTE_1(60, "After 1 minute"),
    MINUTES_5(300, "After 5 minutes")
}

data class BiometricCapability(
    val strongBiometricAvailable: Boolean = false,
    val deviceCredentialAvailable: Boolean = false,
    val statusDescription: String = ""
) {
    val anyDeviceSecurityAvailable: Boolean
        get() = strongBiometricAvailable || deviceCredentialAvailable
}

data class SecuritySettings(
    val securityConfigured: Boolean = false,
    val appLockEnabled: Boolean = false,
    val method: SecurityMethod = SecurityMethod.NONE,
    val fingerprintQuickUnlockEnabled: Boolean = false,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.IMMEDIATE,
    val lockOnAppClose: Boolean = true,
    val hasSemPinSet: Boolean = false,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

data class NotificationPreferences(
    val expenseReminders: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 30,
    val transactionConfirmations: Boolean = true,
    val budgetAlerts: Boolean = true,
    val dailySummary: Boolean = false,
    val weeklyReport: Boolean = true,
    val cloudBackupAlerts: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7
)

data class UserProfile(
    val uid: String,
    val displayName: String,
    val email: String,
    val appUserCode: String = "",
    val upiId: String = "",
    val upiVerified: Boolean = false,
    val openingBalance: Double,
    val monthlyBudget: Double,
    val currencySymbol: String = "₹",
    val setupCompleted: Boolean = false,
    val lastBackupMillis: Long? = null,
    val preferences: NotificationPreferences = NotificationPreferences()
)

enum class DateFilterPreset(val label: String) {
    ALL("All Time"),
    TODAY("Today"),
    LAST_7_DAYS("7 Days"),
    THIS_MONTH("This Month"),
    LAST_MONTH("Last Month"),
    CUSTOM("Custom Range")
}

data class TransactionFilter(
    val searchQuery: String = "",
    val datePreset: DateFilterPreset = DateFilterPreset.ALL,
    val startDate: String? = null, // YYYY-MM-DD
    val endDate: String? = null,   // YYYY-MM-DD
    val selectedCategories: Set<ExpenseCategory> = emptySet(),
    val selectedPaymentMethods: Set<String> = emptySet(),
    val transactionType: TransactionType? = null
) {
    val activeCount: Int
        get() = listOf(
            searchQuery.isNotBlank(),
            datePreset != DateFilterPreset.ALL || startDate != null || endDate != null,
            selectedCategories.isNotEmpty(),
            selectedPaymentMethods.isNotEmpty(),
            transactionType != null
        ).count { it }

    val hasActiveFilters: Boolean
        get() = activeCount > 0
}

data class FilteredLedgerState(
    val entries: List<LedgerEntry> = emptyList(),
    val totalTransactionCount: Int = 0,
    val filteredExpenseTotal: Double = 0.0,
    val filteredIncomeTotal: Double = 0.0,
    val filteredNetTotal: Double = 0.0
)

enum class GroupType(val displayName: String) {
    ROOMMATES("Roommates"),
    FRIENDS("Friends"),
    FAMILY("Family"),
    TRIP_GROUP("Trip Group"),
    CUSTOM_GROUP("Custom Group");

    companion object {
        fun fromFirestore(raw: String): GroupType =
            entries.firstOrNull { it.displayName == raw || it.name == raw } ?: CUSTOM_GROUP
    }
}

enum class GroupMemberRole { ADMIN, MEMBER }
enum class GroupMemberStatus { ACTIVE, LEFT }

data class SplitGroup(
    val id: String,
    val name: String,
    val groupType: GroupType,
    val iconKey: String,
    val joinCode: String,
    val creatorUid: String,
    val creatorName: String,
    val memberUids: List<String>,
    val settlementDueDate: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class GroupMember(
    val id: String,
    val groupId: String,
    val uid: String,
    val displayName: String,
    val appUserCode: String,
    val role: GroupMemberRole = GroupMemberRole.MEMBER,
    val status: GroupMemberStatus = GroupMemberStatus.ACTIVE,
    val upiId: String = "",
    val upiVerified: Boolean = false,
    val joinedAtMillis: Long,
    val updatedAtMillis: Long
)

enum class InviteMethod { APP_ID, EMAIL, SMS, WHATSAPP, IN_APP }
enum class InvitationStatus { PENDING, ACCEPTED, REJECTED, CANCELLED }

data class GroupInvitation(
    val id: String,
    val groupId: String,
    val groupName: String,
    val groupType: GroupType,
    val inviterUid: String,
    val inviterName: String,
    val inviteMethod: InviteMethod,
    val targetUid: String = "",
    val targetAppCode: String = "",
    val targetEmail: String = "",
    val targetPhoneMasked: String = "",
    val inviteToken: String,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

enum class SplitType(val label: String) {
    EQUAL("Equal Split"),
    CUSTOM_AMOUNT("Custom Amount"),
    PERCENTAGE("Percentage Split"),
    SHARES("Individual Shares")
}

data class GroupExpense(
    val id: String,
    val groupId: String,
    val memberUids: List<String>,
    val paidByUid: String,
    val paidByName: String,
    val amount: Double,
    val description: String,
    val category: ExpenseCategory,
    val customCategoryDetail: String = "",
    val transactionDate: String,
    val transactionTime: String,
    val timestampMillis: Long,
    val splitType: SplitType,
    val participantUids: List<String>,
    val shares: Map<String, Double>,
    val receiptReference: String = "",
    val note: String = "",
    val deleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

enum class SettlementStatus(val label: String) {
    PENDING("Pending"),
    PAYMENT_INITIATED("Payment Initiated"),
    AWAITING_CONFIRMATION("Awaiting Confirmation"),
    COMPLETED("Completed"),
    REJECTED("Rejected"),
    FAILED("Failed")
}

enum class SettlementPaymentMethod { UPI, QR, MANUAL }
enum class SettlementDueCategory { UPCOMING, DUE, OVERDUE, COMPLETED }

data class Settlement(
    val id: String,
    val groupId: String,
    val groupName: String,
    val memberUids: List<String>,
    val payerUid: String,
    val payerName: String,
    val receiverUid: String,
    val receiverName: String,
    val receiverUpiId: String = "",
    val amount: Double,
    val dueDate: String = "",
    val reminderEnabled: Boolean = true,
    val status: SettlementStatus = SettlementStatus.PENDING,
    val paymentMethod: SettlementPaymentMethod = SettlementPaymentMethod.UPI,
    val paymentApp: String = "",
    val paymentReference: String = "",
    val idempotencyKey: String,
    val payerConfirmedAtMillis: Long? = null,
    val receiverConfirmedAtMillis: Long? = null,
    val note: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class MemberBalance(
    val uid: String,
    val displayName: String,
    val appUserCode: String,
    val upiId: String,
    val upiVerified: Boolean,
    val role: GroupMemberRole,
    val status: GroupMemberStatus,
    val totalPaid: Double,
    val totalFairShare: Double,
    val settledPaid: Double,
    val settledReceived: Double,
    val netBalance: Double
)

data class SimplifiedDebt(
    val fromUid: String,
    val fromName: String,
    val toUid: String,
    val toName: String,
    val toUpiId: String,
    val toUpiVerified: Boolean,
    val amount: Double
)

data class GroupBalanceSummary(
    val totalGroupExpenses: Double = 0.0,
    val memberBalances: List<MemberBalance> = emptyList(),
    val simplifiedDebts: List<SimplifiedDebt> = emptyList()
)


