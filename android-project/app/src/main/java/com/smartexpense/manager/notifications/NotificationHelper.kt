package com.smartexpense.manager.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartexpense.manager.MainActivity
import com.smartexpense.manager.domain.model.NotificationPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_REMINDERS = "expense_reminders"
        const val CHANNEL_TX_CONFIRM = "transaction_confirmations"
        const val CHANNEL_BUDGET = "budget_alerts"
        const val CHANNEL_DAILY = "daily_summary"
        const val CHANNEL_WEEKLY = "weekly_report"
        const val CHANNEL_BACKUP = "cloud_backup"
        const val CHANNEL_ACCOUNT = "account_security"
    }

    fun createAllChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(CHANNEL_REMINDERS, "Expense Reminders", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_TX_CONFIRM, "Transaction Confirmations", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_BUDGET, "Budget Alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_DAILY, "Daily Expense Summary", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_WEEKLY, "Weekly Financial Report", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_BACKUP, "Cloud Backup Status", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_ACCOUNT, "Account & Security", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannels(channels)
    }

    private fun canPost(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isQuietHours(prefs: NotificationPreferences): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val hour = LocalTime.now().hour
        return if (prefs.quietStartHour > prefs.quietEndHour) {
            hour >= prefs.quietStartHour || hour < prefs.quietEndHour
        } else {
            hour in prefs.quietStartHour until prefs.quietEndHour
        }
    }

    fun showExpenseReminder(prefs: NotificationPreferences) {
        if (!prefs.expenseReminders || isQuietHours(prefs) || !canPost()) return
        val intent = deepLinkIntent("smartexpense://dashboard")
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Log today's expenses")
            .setContentText("Tap to record cash or UPI transactions in Smart Expense Manager.")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1001, notification)
    }

    /**
     * Asks "Where did you spend this money?" with direct notification category actions
     * without leaking sensitive account details on lockscreens.
     */
    fun showTransactionConfirmation(
        prefs: NotificationPreferences,
        txDraftId: String,
        merchantLabel: String,
        amountFormatted: String
    ) {
        if (!prefs.transactionConfirmations || isQuietHours(prefs) || !canPost()) return
        val notificationId = txDraftId.hashCode()
        val categories = listOf("Food", "Travel", "Shopping", "Groceries", "Bills", "Other")
        val builder = NotificationCompat.Builder(context, CHANNEL_TX_CONFIRM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Where did you spend this money?")
            .setContentText("Confirm category for $merchantLabel ($amountFormatted)")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(deepLinkIntent("smartexpense://ledger"))
            .setAutoCancel(true)

        categories.take(3).forEachIndexed { idx, category ->
            val actionIntent = Intent(context, QuickCategorizeReceiver::class.java).apply {
                putExtra("tx_id", txDraftId)
                putExtra("category", category)
                putExtra("notification_id", notificationId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                notificationId + idx,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, category, pi)
        }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun showBudgetThresholdAlert(prefs: NotificationPreferences, thresholdPercent: Int) {
        if (!prefs.budgetAlerts || isQuietHours(prefs) || !canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_BUDGET)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Monthly budget alert: $thresholdPercent% reached")
            .setContentText("You have used $thresholdPercent% of your monthly spending budget.")
            .setContentIntent(deepLinkIntent("smartexpense://analytics"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(2000 + thresholdPercent, notification)
    }

    fun showDailySummary(prefs: NotificationPreferences, summaryLine: String) {
        if (!prefs.dailySummary || isQuietHours(prefs) || !canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Daily Expense Summary")
            .setContentText(summaryLine)
            .setContentIntent(deepLinkIntent("smartexpense://dashboard"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(3001, notification)
    }

    fun showWeeklyReport(prefs: NotificationPreferences, summaryLine: String) {
        if (!prefs.weeklyReport || isQuietHours(prefs) || !canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Weekly Financial Report")
            .setContentText(summaryLine)
            .setContentIntent(deepLinkIntent("smartexpense://analytics"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(4001, notification)
    }

    fun showCloudBackupStatus(prefs: NotificationPreferences, success: Boolean, message: String) {
        if (!prefs.cloudBackupAlerts || !canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(if (success) "Cloud Backup Complete" else "Cloud Backup Deferred")
            .setContentText(message)
            .setContentIntent(deepLinkIntent("smartexpense://settings"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(5001, notification)
    }

    fun showAccountSecurityNotice(title: String, message: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(deepLinkIntent("smartexpense://settings"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(6001, notification)
    }

    private fun deepLinkIntent(uriString: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString), context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            uriString.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
