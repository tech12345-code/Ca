package com.smartexpense.manager.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smartexpense.manager.domain.model.EntrySource
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberRole
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.GroupType
import com.smartexpense.manager.domain.model.InvitationStatus
import com.smartexpense.manager.domain.model.InviteMethod
import com.smartexpense.manager.domain.model.NotificationPreferences
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementPaymentMethod
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SplitType
import com.smartexpense.manager.domain.model.SyncStatus
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["uid", "timestampMillis"]),
        Index(value = ["uid", "syncStatus", "timestampMillis"]),
        Index(value = ["uid", "category", "timestampMillis"]),
        Index(value = ["uid", "paymentMethod", "timestampMillis"]),
        Index(value = ["uid", "transactionDate"]),
        Index(value = ["referenceId"])
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val type: String,
    val amount: Double,
    val description: String,
    val merchant: String,
    val category: String,
    val customCategoryDetail: String = "",
    val paymentMethod: String,
    val sourceOfMoney: String,
    val note: String,
    val transactionDate: String,
    val transactionTime: String,
    val timestampMillis: Long,
    val referenceId: String,
    val invoiceNumber: String,
    val entrySource: String,
    val syncStatus: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain(): Transaction = Transaction(
        id = id,
        uid = uid,
        type = TransactionType.valueOf(type),
        amount = amount,
        description = description,
        merchant = merchant,
        category = runCatching { ExpenseCategory.valueOf(category) }.getOrDefault(ExpenseCategory.Other),
        customCategoryDetail = customCategoryDetail,
        paymentMethod = paymentMethod,
        sourceOfMoney = sourceOfMoney,
        note = note,
        transactionDate = transactionDate,
        transactionTime = transactionTime,
        timestampMillis = timestampMillis,
        referenceId = referenceId,
        invoiceNumber = invoiceNumber,
        entrySource = runCatching { EntrySource.valueOf(entrySource) }.getOrDefault(EntrySource.MANUAL),
        syncStatus = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.PENDING_UPLOAD),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(tx: Transaction): TransactionEntity = TransactionEntity(
            id = tx.id,
            uid = tx.uid,
            type = tx.type.name,
            amount = tx.amount,
            description = tx.description,
            merchant = tx.merchant,
            category = tx.category.name,
            customCategoryDetail = tx.customCategoryDetail,
            paymentMethod = tx.paymentMethod,
            sourceOfMoney = tx.sourceOfMoney,
            note = tx.note,
            transactionDate = tx.transactionDate,
            transactionTime = tx.transactionTime,
            timestampMillis = tx.timestampMillis,
            referenceId = tx.referenceId,
            invoiceNumber = tx.invoiceNumber,
            entrySource = tx.entrySource.name,
            syncStatus = tx.syncStatus.name,
            createdAtMillis = tx.createdAtMillis,
            updatedAtMillis = tx.updatedAtMillis
        )
    }
}

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String,
    val appUserCode: String = "",
    val upiId: String = "",
    val upiVerified: Boolean = false,
    val openingBalance: Double,
    val monthlyBudget: Double,
    val currencySymbol: String,
    val setupCompleted: Boolean,
    val lastBackupMillis: Long?,
    val expenseReminders: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val transactionConfirmations: Boolean,
    val budgetAlerts: Boolean,
    val dailySummary: Boolean,
    val weeklyReport: Boolean,
    val cloudBackupAlerts: Boolean,
    val quietHoursEnabled: Boolean,
    val quietStartHour: Int,
    val quietEndHour: Int
) {
    fun toDomain(): UserProfile = UserProfile(
        uid = uid,
        displayName = displayName,
        email = email,
        appUserCode = appUserCode,
        upiId = upiId,
        upiVerified = upiVerified,
        openingBalance = openingBalance,
        monthlyBudget = monthlyBudget,
        currencySymbol = currencySymbol,
        setupCompleted = setupCompleted,
        lastBackupMillis = lastBackupMillis,
        preferences = NotificationPreferences(
            expenseReminders = expenseReminders,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
            transactionConfirmations = transactionConfirmations,
            budgetAlerts = budgetAlerts,
            dailySummary = dailySummary,
            weeklyReport = weeklyReport,
            cloudBackupAlerts = cloudBackupAlerts,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietEndHour = quietEndHour
        )
    )

    companion object {
        fun fromDomain(profile: UserProfile): UserProfileEntity = UserProfileEntity(
            uid = profile.uid,
            displayName = profile.displayName,
            email = profile.email,
            appUserCode = profile.appUserCode,
            upiId = profile.upiId,
            upiVerified = profile.upiVerified,
            openingBalance = profile.openingBalance,
            monthlyBudget = profile.monthlyBudget,
            currencySymbol = profile.currencySymbol,
            setupCompleted = profile.setupCompleted,
            lastBackupMillis = profile.lastBackupMillis,
            expenseReminders = profile.preferences.expenseReminders,
            reminderHour = profile.preferences.reminderHour,
            reminderMinute = profile.preferences.reminderMinute,
            transactionConfirmations = profile.preferences.transactionConfirmations,
            budgetAlerts = profile.preferences.budgetAlerts,
            dailySummary = profile.preferences.dailySummary,
            weeklyReport = profile.preferences.weeklyReport,
            cloudBackupAlerts = profile.preferences.cloudBackupAlerts,
            quietHoursEnabled = profile.preferences.quietHoursEnabled,
            quietStartHour = profile.preferences.quietStartHour,
            quietEndHour = profile.preferences.quietEndHour
        )
    }
}

@Entity(tableName = "budget_alert_state")
data class BudgetAlertStateEntity(
    @PrimaryKey val alertKey: String,
    val uid: String,
    val monthKey: String,
    val thresholdPercent: Int,
    val triggeredAtMillis: Long
)

private fun listToJson(items: List<String>): String = JSONArray(items).toString()
private fun jsonToList(raw: String): List<String> = runCatching {
    val arr = JSONArray(raw)
    List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
}.getOrDefault(emptyList())

private fun mapToJson(map: Map<String, Double>): String {
    val obj = JSONObject()
    map.forEach { (k, v) -> obj.put(k, v) }
    return obj.toString()
}

private fun jsonToMap(raw: String): Map<String, Double> = runCatching {
    val obj = JSONObject(raw)
    val result = linkedMapOf<String, Double>()
    obj.keys().forEach { key -> result[key] = obj.optDouble(key, 0.0) }
    result
}.getOrDefault(emptyMap())

@Entity(tableName = "split_groups")
data class SplitGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val groupType: String,
    val iconKey: String,
    val joinCode: String,
    val creatorUid: String,
    val creatorName: String,
    val memberUidsJson: String,
    val settlementDueDate: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain() = SplitGroup(
        id = id,
        name = name,
        groupType = GroupType.fromFirestore(groupType),
        iconKey = iconKey,
        joinCode = joinCode,
        creatorUid = creatorUid,
        creatorName = creatorName,
        memberUids = jsonToList(memberUidsJson),
        settlementDueDate = settlementDueDate,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(g: SplitGroup) = SplitGroupEntity(
            id = g.id,
            name = g.name,
            groupType = g.groupType.displayName,
            iconKey = g.iconKey,
            joinCode = g.joinCode,
            creatorUid = g.creatorUid,
            creatorName = g.creatorName,
            memberUidsJson = listToJson(g.memberUids),
            settlementDueDate = g.settlementDueDate,
            createdAtMillis = g.createdAtMillis,
            updatedAtMillis = g.updatedAtMillis
        )
    }
}

@Entity(tableName = "group_members", indices = [Index(value = ["groupId"])])
data class GroupMemberEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val uid: String,
    val displayName: String,
    val appUserCode: String,
    val role: String,
    val status: String,
    val upiId: String,
    val upiVerified: Boolean,
    val joinedAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain() = GroupMember(
        id = id,
        groupId = groupId,
        uid = uid,
        displayName = displayName,
        appUserCode = appUserCode,
        role = runCatching { GroupMemberRole.valueOf(role) }.getOrDefault(GroupMemberRole.MEMBER),
        status = runCatching { GroupMemberStatus.valueOf(status) }.getOrDefault(GroupMemberStatus.ACTIVE),
        upiId = upiId,
        upiVerified = upiVerified,
        joinedAtMillis = joinedAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(m: GroupMember) = GroupMemberEntity(
            id = m.id,
            groupId = m.groupId,
            uid = m.uid,
            displayName = m.displayName,
            appUserCode = m.appUserCode,
            role = m.role.name,
            status = m.status.name,
            upiId = m.upiId,
            upiVerified = m.upiVerified,
            joinedAtMillis = m.joinedAtMillis,
            updatedAtMillis = m.updatedAtMillis
        )
    }
}

@Entity(tableName = "group_invitations")
data class GroupInvitationEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val groupName: String,
    val groupType: String,
    val inviterUid: String,
    val inviterName: String,
    val inviteMethod: String,
    val targetUid: String,
    val targetAppCode: String,
    val targetEmail: String,
    val targetPhoneMasked: String,
    val inviteToken: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain() = GroupInvitation(
        id = id,
        groupId = groupId,
        groupName = groupName,
        groupType = GroupType.fromFirestore(groupType),
        inviterUid = inviterUid,
        inviterName = inviterName,
        inviteMethod = runCatching { InviteMethod.valueOf(inviteMethod) }.getOrDefault(InviteMethod.IN_APP),
        targetUid = targetUid,
        targetAppCode = targetAppCode,
        targetEmail = targetEmail,
        targetPhoneMasked = targetPhoneMasked,
        inviteToken = inviteToken,
        status = runCatching { InvitationStatus.valueOf(status) }.getOrDefault(InvitationStatus.PENDING),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(inv: GroupInvitation) = GroupInvitationEntity(
            id = inv.id,
            groupId = inv.groupId,
            groupName = inv.groupName,
            groupType = inv.groupType.displayName,
            inviterUid = inv.inviterUid,
            inviterName = inv.inviterName,
            inviteMethod = inv.inviteMethod.name,
            targetUid = inv.targetUid,
            targetAppCode = inv.targetAppCode,
            targetEmail = inv.targetEmail,
            targetPhoneMasked = inv.targetPhoneMasked,
            inviteToken = inv.inviteToken,
            status = inv.status.name,
            createdAtMillis = inv.createdAtMillis,
            updatedAtMillis = inv.updatedAtMillis
        )
    }
}

@Entity(tableName = "group_expenses", indices = [Index(value = ["groupId", "timestampMillis"])])
data class GroupExpenseEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val memberUidsJson: String,
    val paidByUid: String,
    val paidByName: String,
    val amount: Double,
    val description: String,
    val category: String,
    val customCategoryDetail: String,
    val transactionDate: String,
    val transactionTime: String,
    val timestampMillis: Long,
    val splitType: String,
    val participantUidsJson: String,
    val sharesJson: String,
    val receiptReference: String,
    val note: String,
    val deleted: Boolean,
    val syncStatus: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain() = GroupExpense(
        id = id,
        groupId = groupId,
        memberUids = jsonToList(memberUidsJson),
        paidByUid = paidByUid,
        paidByName = paidByName,
        amount = amount,
        description = description,
        category = runCatching { ExpenseCategory.valueOf(category) }.getOrDefault(ExpenseCategory.Other),
        customCategoryDetail = customCategoryDetail,
        transactionDate = transactionDate,
        transactionTime = transactionTime,
        timestampMillis = timestampMillis,
        splitType = runCatching { SplitType.valueOf(splitType) }.getOrDefault(SplitType.EQUAL),
        participantUids = jsonToList(participantUidsJson),
        shares = jsonToMap(sharesJson),
        receiptReference = receiptReference,
        note = note,
        deleted = deleted,
        syncStatus = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.PENDING_UPLOAD),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(e: GroupExpense) = GroupExpenseEntity(
            id = e.id,
            groupId = e.groupId,
            memberUidsJson = listToJson(e.memberUids),
            paidByUid = e.paidByUid,
            paidByName = e.paidByName,
            amount = e.amount,
            description = e.description,
            category = e.category.name,
            customCategoryDetail = e.customCategoryDetail,
            transactionDate = e.transactionDate,
            transactionTime = e.transactionTime,
            timestampMillis = e.timestampMillis,
            splitType = e.splitType.name,
            participantUidsJson = listToJson(e.participantUids),
            sharesJson = mapToJson(e.shares),
            receiptReference = e.receiptReference,
            note = e.note,
            deleted = e.deleted,
            syncStatus = e.syncStatus.name,
            createdAtMillis = e.createdAtMillis,
            updatedAtMillis = e.updatedAtMillis
        )
    }
}

@Entity(tableName = "settlements", indices = [Index(value = ["groupId", "status"])])
data class SettlementEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val groupName: String,
    val memberUidsJson: String,
    val payerUid: String,
    val payerName: String,
    val receiverUid: String,
    val receiverName: String,
    val receiverUpiId: String,
    val amount: Double,
    val dueDate: String,
    val reminderEnabled: Boolean,
    val status: String,
    val paymentMethod: String,
    val paymentApp: String,
    val paymentReference: String,
    val idempotencyKey: String,
    val payerConfirmedAtMillis: Long?,
    val receiverConfirmedAtMillis: Long?,
    val note: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    fun toDomain() = Settlement(
        id = id,
        groupId = groupId,
        groupName = groupName,
        memberUids = jsonToList(memberUidsJson),
        payerUid = payerUid,
        payerName = payerName,
        receiverUid = receiverUid,
        receiverName = receiverName,
        receiverUpiId = receiverUpiId,
        amount = amount,
        dueDate = dueDate,
        reminderEnabled = reminderEnabled,
        status = runCatching { SettlementStatus.valueOf(status) }.getOrDefault(SettlementStatus.PENDING),
        paymentMethod = runCatching { SettlementPaymentMethod.valueOf(paymentMethod) }.getOrDefault(SettlementPaymentMethod.UPI),
        paymentApp = paymentApp,
        paymentReference = paymentReference,
        idempotencyKey = idempotencyKey,
        payerConfirmedAtMillis = payerConfirmedAtMillis,
        receiverConfirmedAtMillis = receiverConfirmedAtMillis,
        note = note,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(s: Settlement) = SettlementEntity(
            id = s.id,
            groupId = s.groupId,
            groupName = s.groupName,
            memberUidsJson = listToJson(s.memberUids),
            payerUid = s.payerUid,
            payerName = s.payerName,
            receiverUid = s.receiverUid,
            receiverName = s.receiverName,
            receiverUpiId = s.receiverUpiId,
            amount = s.amount,
            dueDate = s.dueDate,
            reminderEnabled = s.reminderEnabled,
            status = s.status.name,
            paymentMethod = s.paymentMethod.name,
            paymentApp = s.paymentApp,
            paymentReference = s.paymentReference,
            idempotencyKey = s.idempotencyKey,
            payerConfirmedAtMillis = s.payerConfirmedAtMillis,
            receiverConfirmedAtMillis = s.receiverConfirmedAtMillis,
            note = s.note,
            createdAtMillis = s.createdAtMillis,
            updatedAtMillis = s.updatedAtMillis
        )
    }
}

