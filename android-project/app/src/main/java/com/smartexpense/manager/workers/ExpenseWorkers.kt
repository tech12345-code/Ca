package com.smartexpense.manager.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.smartexpense.manager.data.local.TransactionDao
import com.smartexpense.manager.data.local.UserProfileDao
import com.smartexpense.manager.data.repository.ExpenseRepository
import com.smartexpense.manager.domain.calculator.BalanceCalculator
import com.smartexpense.manager.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: ExpenseRepository,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: auth.currentUser?.uid ?: return Result.success()
        return repository.syncPendingToCloud(uid).fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}

@HiltWorker
class ExpenseReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val profileDao: UserProfileDao,
    private val notificationHelper: NotificationHelper,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.success()
        val profile = profileDao.getProfile(uid)?.toDomain() ?: return Result.success()
        notificationHelper.showExpenseReminder(profile.preferences)
        return Result.success()
    }
}

@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val profileDao: UserProfileDao,
    private val transactionDao: TransactionDao,
    private val calculator: BalanceCalculator,
    private val notificationHelper: NotificationHelper,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.success()
        val profile = profileDao.getProfile(uid)?.toDomain() ?: return Result.success()
        val txs = transactionDao.getAllTransactions(uid).map { it.toDomain() }
        val today = LocalDate.now().toString()
        val todaySpent = txs.filter { it.transactionDate == today && it.type.name == "EXPENSE" }
            .sumOf { it.amount }
        val summary = calculator.calculate(profile.openingBalance, profile.monthlyBudget, txs, YearMonth.now())
        val line = "Today: ${profile.currencySymbol}${"%.0f".format(todaySpent)} · Remaining budget: ${profile.currencySymbol}${"%.0f".format(summary.remainingBudget)}"
        notificationHelper.showDailySummary(profile.preferences, line)
        return Result.success()
    }
}

@HiltWorker
class WeeklyReportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val profileDao: UserProfileDao,
    private val transactionDao: TransactionDao,
    private val notificationHelper: NotificationHelper,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.success()
        val profile = profileDao.getProfile(uid)?.toDomain() ?: return Result.success()
        val txs = transactionDao.getAllTransactions(uid).map { it.toDomain() }
        val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val weekExpenses = txs.filter { it.timestampMillis >= sevenDaysAgo && it.type.name == "EXPENSE" }
        val total = weekExpenses.sumOf { it.amount }
        val topCat = weekExpenses.groupBy { it.category.displayName }
            .maxByOrNull { it.value.sumOf { tx -> tx.amount } }?.key ?: "None"
        notificationHelper.showWeeklyReport(
            profile.preferences,
            "7-day spend: ${profile.currencySymbol}${"%.0f".format(total)} · Top category: $topCat"
        )
        return Result.success()
    }
}

object WorkerScheduler {
    fun enqueueCloudSync(context: Context, uid: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("uid" to uid))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cloud_sync_$uid",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun schedulePeriodicTasks(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "daily_expense_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExpenseReminderWorker>(24, TimeUnit.HOURS).build()
        )
        wm.enqueueUniquePeriodicWork(
            "daily_summary_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS).build()
        )
        wm.enqueueUniquePeriodicWork(
            "weekly_report_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS).build()
        )
    }
}
