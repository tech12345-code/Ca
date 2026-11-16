package com.smartexpense.manager.data.repository

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.smartexpense.manager.data.local.BudgetAlertDao
import com.smartexpense.manager.data.local.BudgetAlertStateEntity
import com.smartexpense.manager.data.local.TransactionDao
import com.smartexpense.manager.data.local.TransactionEntity
import com.smartexpense.manager.data.local.UserProfileDao
import com.smartexpense.manager.data.local.UserProfileEntity
import com.smartexpense.manager.data.remote.FirestoreSyncService
import com.smartexpense.manager.domain.calculator.BalanceCalculator
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.FilteredLedgerState
import com.smartexpense.manager.domain.model.SyncStatus
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionFilter
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.model.UserProfile
import com.smartexpense.manager.notifications.NotificationHelper
import com.smartexpense.manager.workers.WorkerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val profileDao: UserProfileDao,
    private val alertDao: BudgetAlertDao,
    private val firestoreSync: FirestoreSyncService,
    private val calculator: BalanceCalculator,
    private val notificationHelper: NotificationHelper
) {
    fun observeProfile(uid: String): Flow<UserProfile?> =
        profileDao.observeProfile(uid).map { it?.toDomain() }

    fun observeBalanceSummary(uid: String, targetMonth: YearMonth = YearMonth.now()): Flow<BalanceSummary> {
        return combine(
            profileDao.observeProfile(uid),
            transactionDao.observeTransactions(uid)
        ) { profileEntity, txEntities ->
            val profile = profileEntity?.toDomain()
            val transactions = txEntities.map { it.toDomain() }
            calculator.calculate(
                openingBalance = profile?.openingBalance ?: 0.0,
                monthlyBudget = profile?.monthlyBudget ?: 0.0,
                transactions = transactions,
                targetMonth = targetMonth
            )
        }
    }

    /**
     * Executes an indexed SQLite query in Room via @RawQuery while preserving each row's
     * true chronological running balance from the full ledger.
     */
    fun observeFilteredLedger(uid: String, filter: TransactionFilter): Flow<FilteredLedgerState> {
        val sqlQuery = buildRoomFilterQuery(uid, filter)
        return combine(
            observeBalanceSummary(uid),
            transactionDao.observeFilteredTransactions(sqlQuery)
        ) { fullSummary, filteredEntities ->
            val runningBalanceById = fullSummary.ledger.associate {
                it.transaction.id to it.runningBalanceAfter
            }
            val filteredEntries = filteredEntities.map { entity ->
                val domain = entity.toDomain()
                com.smartexpense.manager.domain.model.LedgerEntry(
                    transaction = domain,
                    runningBalanceAfter = runningBalanceById[domain.id] ?: fullSummary.availableBalance
                )
            }
            var expTotal = 0.0
            var incTotal = 0.0
            for (entry in filteredEntries) {
                if (entry.transaction.type == TransactionType.EXPENSE) {
                    expTotal += entry.transaction.amount
                } else {
                    incTotal += entry.transaction.amount
                }
            }
            FilteredLedgerState(
                entries = filteredEntries,
                totalTransactionCount = fullSummary.ledger.size,
                filteredExpenseTotal = kotlin.math.round(expTotal * 100.0) / 100.0,
                filteredIncomeTotal = kotlin.math.round(incTotal * 100.0) / 100.0,
                filteredNetTotal = kotlin.math.round((incTotal - expTotal) * 100.0) / 100.0
            )
        }
    }

    fun observePaymentMethods(uid: String): Flow<List<String>> =
        transactionDao.observeDistinctPaymentMethods(uid)

    suspend fun queryRemoteFilteredTransactions(
        uid: String,
        filter: TransactionFilter
    ): Result<List<Transaction>> = runCatching {
        firestoreSync.queryFilteredTransactions(uid, filter)
    }

    private fun buildRoomFilterQuery(uid: String, filter: TransactionFilter): SimpleSQLiteQuery {
        val sql = StringBuilder(
            "SELECT * FROM transactions WHERE uid = ? AND syncStatus != 'PENDING_DELETE'"
        )
        val args = mutableListOf<Any>(uid)

        filter.transactionType?.let { txType ->
            sql.append(" AND type = ?")
            args.add(txType.name)
        }

        filter.startDate?.takeIf { it.isNotBlank() }?.let { start ->
            sql.append(" AND timestampMillis >= ?")
            args.add(calculator.toTimestampMillis(start, "00:00"))
        }

        filter.endDate?.takeIf { it.isNotBlank() }?.let { end ->
            sql.append(" AND timestampMillis <= ?")
            args.add(calculator.toTimestampMillis(end, "23:59") + 59_999L)
        }

        if (filter.selectedCategories.isNotEmpty()) {
            val placeholders = filter.selectedCategories.joinToString(",") { "?" }
            sql.append(" AND category IN ($placeholders)")
            filter.selectedCategories.forEach { args.add(it.name) }
        }

        if (filter.selectedPaymentMethods.isNotEmpty()) {
            val placeholders = filter.selectedPaymentMethods.joinToString(",") { "?" }
            sql.append(" AND paymentMethod IN ($placeholders)")
            filter.selectedPaymentMethods.forEach { args.add(it) }
        }

        val q = filter.searchQuery.trim()
        if (q.isNotBlank()) {
            val pattern = "%$q%"
            sql.append(" AND (description LIKE ? COLLATE NOCASE OR merchant LIKE ? COLLATE NOCASE OR customCategoryDetail LIKE ? COLLATE NOCASE OR note LIKE ? COLLATE NOCASE OR referenceId LIKE ? COLLATE NOCASE)")
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }

        sql.append(" ORDER BY timestampMillis ASC, createdAtMillis ASC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    suspend fun saveProfile(profile: UserProfile) {
        profileDao.upsert(UserProfileEntity.fromDomain(profile))
        runCatching { firestoreSync.upsertUserProfile(profile) }
            .onFailure { WorkerScheduler.enqueueCloudSync(context, profile.uid) }
    }

    suspend fun saveTransaction(tx: Transaction): Result<Unit> {
        if (tx.amount <= 0.0 || tx.amount.isNaN() || tx.amount.isInfinite()) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero."))
        }
        if (tx.description.isBlank()) {
            return Result.failure(IllegalArgumentException("Description is required."))
        }
        if (tx.referenceId.isNotBlank()) {
            val duplicate = transactionDao.findByReferenceId(tx.uid, tx.referenceId)
            if (duplicate != null && duplicate.id != tx.id) {
                return Result.failure(IllegalStateException("Duplicate transaction reference ID: ${tx.referenceId}"))
            }
        }

        val pendingTx = tx.copy(syncStatus = SyncStatus.PENDING_UPLOAD)
        transactionDao.upsert(TransactionEntity.fromDomain(pendingTx))

        checkAndNotifyBudgetThresholds(tx.uid)

        runCatching {
            firestoreSync.upsertTransaction(pendingTx)
            transactionDao.upsert(TransactionEntity.fromDomain(pendingTx.copy(syncStatus = SyncStatus.SYNCED)))
        }.onFailure {
            WorkerScheduler.enqueueCloudSync(context, tx.uid)
        }
        return Result.success(Unit)
    }

    suspend fun deleteTransaction(uid: String, txId: String) {
        val existing = transactionDao.getById(txId) ?: return
        transactionDao.upsert(existing.copy(syncStatus = SyncStatus.PENDING_DELETE))
        runCatching {
            firestoreSync.deleteTransaction(uid, txId)
            transactionDao.hardDelete(txId)
        }.onFailure {
            WorkerScheduler.enqueueCloudSync(context, uid)
        }
    }

    suspend fun syncPendingToCloud(uid: String): Result<Int> = runCatching {
        val profile = profileDao.getProfile(uid)?.toDomain()
        if (profile != null) {
            firestoreSync.upsertUserProfile(profile)
        }
        val pending = transactionDao.getPendingSyncTransactions(uid)
        var syncedCount = 0
        for (item in pending) {
            if (item.syncStatus == SyncStatus.PENDING_DELETE.name) {
                firestoreSync.deleteTransaction(uid, item.id)
                transactionDao.hardDelete(item.id)
            } else {
                val domain = item.toDomain()
                firestoreSync.upsertTransaction(domain)
                transactionDao.upsert(item.copy(syncStatus = SyncStatus.SYNCED.name))
            }
            syncedCount++
        }
        firestoreSync.markBackupTimestamp(uid)
        if (profile?.preferences?.cloudBackupAlerts == true) {
            notificationHelper.showCloudBackupStatus(
                prefs = profile.preferences,
                success = true,
                message = "Cloud backup synced ($syncedCount changes)."
            )
        }
        syncedCount
    }.onFailure { err ->
        val profile = profileDao.getProfile(uid)?.toDomain()
        if (profile?.preferences?.cloudBackupAlerts == true) {
            notificationHelper.showCloudBackupStatus(
                prefs = profile.preferences,
                success = false,
                message = "Cloud backup deferred: ${err.message ?: "Network unavailable"}"
            )
        }
    }

    suspend fun restoreFromCloud(uid: String): Result<Int> = runCatching {
        val remoteProfile = firestoreSync.fetchUserProfile(uid)
        if (remoteProfile != null) {
            profileDao.upsert(UserProfileEntity.fromDomain(remoteProfile))
        }
        val remoteTxs = firestoreSync.fetchAllTransactions(uid)
        transactionDao.upsertAll(remoteTxs.map { TransactionEntity.fromDomain(it) })
        if (remoteProfile?.preferences?.cloudBackupAlerts == true) {
            notificationHelper.showCloudBackupStatus(
                prefs = remoteProfile.preferences,
                success = true,
                message = "Restored ${remoteTxs.size} transactions from Cloud Firestore."
            )
        }
        remoteTxs.size
    }

    suspend fun checkAndNotifyBudgetThresholds(uid: String) {
        val profile = profileDao.getProfile(uid)?.toDomain() ?: return
        if (!profile.preferences.budgetAlerts || profile.monthlyBudget <= 0.0) return

        val transactions = transactionDao.getAllTransactions(uid).map { it.toDomain() }
        val currentMonth = YearMonth.now()
        val summary = calculator.calculate(
            openingBalance = profile.openingBalance,
            monthlyBudget = profile.monthlyBudget,
            transactions = transactions,
            targetMonth = currentMonth
        )
        val thresholds = listOf(50, 75, 90, 100)
        val monthKey = currentMonth.toString()

        for (threshold in thresholds) {
            if (summary.budgetUsagePercent >= threshold) {
                val alertKey = "${monthKey}_$threshold"
                if (!alertDao.wasAlertTriggered(uid, alertKey)) {
                    alertDao.markAlertTriggered(
                        BudgetAlertStateEntity(
                            alertKey = alertKey,
                            uid = uid,
                            monthKey = monthKey,
                            thresholdPercent = threshold,
                            triggeredAtMillis = System.currentTimeMillis()
                        )
                    )
                    notificationHelper.showBudgetThresholdAlert(
                        prefs = profile.preferences,
                        thresholdPercent = threshold
                    )
                }
            }
        }
    }

    fun newTransactionId(): String = "tx_${UUID.randomUUID().toString().replace("-", "").take(16)}"
}
