package com.smartexpense.manager.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.smartexpense.manager.data.local.TransactionDao
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.SyncStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class QuickCategorizeReceiver : BroadcastReceiver() {

    @Inject lateinit var transactionDao: TransactionDao

    override fun onReceive(context: Context, intent: Intent) {
        val txId = intent.getStringExtra("tx_id") ?: return
        val categoryStr = intent.getStringExtra("category") ?: "Other"
        val notificationId = intent.getIntExtra("notification_id", -1)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existing = transactionDao.getById(txId)
                if (existing != null) {
                    val safeCategory = runCatching { ExpenseCategory.valueOf(categoryStr).name }
                        .getOrDefault(ExpenseCategory.Other.name)
                    transactionDao.upsert(
                        existing.copy(
                            category = safeCategory,
                            syncStatus = SyncStatus.PENDING_UPLOAD.name,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                }
                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
