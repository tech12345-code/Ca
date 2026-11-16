package com.smartexpense.manager.notifications

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.smartexpense.manager.data.local.UserProfileDao
import com.smartexpense.manager.data.remote.FirestoreSyncService
import com.smartexpense.manager.domain.model.NotificationPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmartExpenseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var firestoreSync: FirestoreSyncService
    @Inject lateinit var profileDao: UserProfileDao
    @Inject lateinit var auth: FirebaseAuth

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = auth.currentUser?.uid ?: return
        val tokenId = token.take(24).replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        scope.launch {
            runCatching { firestoreSync.saveFcmToken(uid, tokenId, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: return
        val uid = auth.currentUser?.uid

        scope.launch {
            val prefs = if (uid != null) {
                profileDao.getProfile(uid)?.toDomain()?.preferences ?: NotificationPreferences()
            } else {
                NotificationPreferences()
            }

            when (type) {
                "EXPENSE_REMINDER" -> notificationHelper.showExpenseReminder(prefs)
                "TRANSACTION_CONFIRMATION" -> notificationHelper.showTransactionConfirmation(
                    prefs = prefs,
                    txDraftId = data["txId"] ?: "pending_tx",
                    merchantLabel = data["merchant"] ?: "Recent Payment",
                    amountFormatted = data["displayLabel"] ?: "Tap to categorize"
                )
                "BUDGET_ALERT" -> {
                    val threshold = data["threshold"]?.toIntOrNull() ?: 75
                    notificationHelper.showBudgetThresholdAlert(prefs, threshold)
                }
                "DAILY_SUMMARY" -> notificationHelper.showDailySummary(
                    prefs = prefs,
                    summaryLine = data["summary"] ?: "Open Smart Expense Manager to review today's budget."
                )
                "WEEKLY_REPORT" -> notificationHelper.showWeeklyReport(
                    prefs = prefs,
                    summaryLine = data["summary"] ?: "Your weekly income and expense breakdown is ready."
                )
                "CLOUD_BACKUP" -> notificationHelper.showCloudBackupStatus(
                    prefs = prefs,
                    success = data["success"] != "false",
                    message = data["summary"] ?: "Cloud sync updated."
                )
                "ACCOUNT_SECURITY" -> notificationHelper.showAccountSecurityNotice(
                    title = data["title"] ?: "Account Security Notice",
                    message = data["summary"] ?: "A sign-in or security event occurred on your account."
                )
            }
        }
    }
}
