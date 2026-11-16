package com.smartexpense.manager.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.smartexpense.manager.domain.calculator.BalanceCalculator
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
import com.smartexpense.manager.domain.model.TransactionFilter
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.model.UserProfile
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteSplitWorkspace(
    val groups: List<SplitGroup>,
    val members: List<GroupMember>,
    val invitations: List<GroupInvitation>,
    val expenses: List<GroupExpense>,
    val settlements: List<Settlement>
)

@Singleton
class FirestoreSyncService @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val calculator: BalanceCalculator
) {
    suspend fun upsertUserProfile(profile: UserProfile) {
        val docRef = firestore.collection("users").document(profile.uid)
        val existing = docRef.get().await()
        val payload = hashMapOf<String, Any>(
            "uid" to profile.uid,
            "displayName" to profile.displayName.take(120),
            "email" to profile.email.take(200),
            "appUserCode" to profile.appUserCode.take(32),
            "upiId" to profile.upiId.take(100),
            "upiVerified" to profile.upiVerified,
            "openingBalance" to profile.openingBalance,
            "monthlyBudget" to profile.monthlyBudget,
            "currencySymbol" to profile.currencySymbol.take(8),
            "setupCompleted" to profile.setupCompleted,
            "notifExpenseReminders" to profile.preferences.expenseReminders,
            "notifReminderHour" to profile.preferences.reminderHour,
            "notifReminderMinute" to profile.preferences.reminderMinute,
            "notifTransactionConfirmations" to profile.preferences.transactionConfirmations,
            "notifBudgetAlerts" to profile.preferences.budgetAlerts,
            "notifDailySummary" to profile.preferences.dailySummary,
            "notifWeeklyReport" to profile.preferences.weeklyReport,
            "notifCloudBackup" to profile.preferences.cloudBackupAlerts,
            "quietHoursEnabled" to profile.preferences.quietHoursEnabled,
            "quietStartHour" to profile.preferences.quietStartHour,
            "quietEndHour" to profile.preferences.quietEndHour,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!existing.exists()) {
            payload["createdAt"] = FieldValue.serverTimestamp()
        }
        docRef.set(payload, SetOptions.merge()).await()
    }

    suspend fun fetchUserProfile(uid: String): UserProfile? {
        val snap = firestore.collection("users").document(uid).get().await()
        if (!snap.exists()) return null
        return UserProfile(
            uid = snap.getString("uid") ?: uid,
            displayName = snap.getString("displayName") ?: "User",
            email = snap.getString("email") ?: "",
            appUserCode = snap.getString("appUserCode") ?: "",
            upiId = snap.getString("upiId") ?: "",
            upiVerified = snap.getBoolean("upiVerified") ?: false,
            openingBalance = snap.getDouble("openingBalance") ?: 0.0,
            monthlyBudget = snap.getDouble("monthlyBudget") ?: 0.0,
            currencySymbol = snap.getString("currencySymbol") ?: "₹",
            setupCompleted = snap.getBoolean("setupCompleted") ?: false,
            lastBackupMillis = snap.getTimestamp("lastBackupAt")?.toDate()?.time,
            preferences = NotificationPreferences(
                expenseReminders = snap.getBoolean("notifExpenseReminders") ?: true,
                reminderHour = (snap.getLong("notifReminderHour") ?: 20L).toInt(),
                reminderMinute = (snap.getLong("notifReminderMinute") ?: 30L).toInt(),
                transactionConfirmations = snap.getBoolean("notifTransactionConfirmations") ?: true,
                budgetAlerts = snap.getBoolean("notifBudgetAlerts") ?: true,
                dailySummary = snap.getBoolean("notifDailySummary") ?: false,
                weeklyReport = snap.getBoolean("notifWeeklyReport") ?: true,
                cloudBackupAlerts = snap.getBoolean("notifCloudBackup") ?: true,
                quietHoursEnabled = snap.getBoolean("quietHoursEnabled") ?: true,
                quietStartHour = (snap.getLong("quietStartHour") ?: 22L).toInt(),
                quietEndHour = (snap.getLong("quietEndHour") ?: 7L).toInt()
            )
        )
    }

    suspend fun upsertTransaction(tx: Transaction) {
        val ref = firestore.collection("users").document(tx.uid)
            .collection("transactions").document(tx.id)
        val existing = ref.get().await()
        val payload = hashMapOf<String, Any>(
            "id" to tx.id,
            "uid" to tx.uid,
            "type" to tx.type.name,
            "amount" to tx.amount,
            "description" to tx.description.take(200),
            "merchant" to tx.merchant.take(120),
            "category" to tx.category.name,
            "customCategoryDetail" to tx.customCategoryDetail.take(120),
            "paymentMethod" to tx.paymentMethod.take(64),
            "sourceOfMoney" to tx.sourceOfMoney.take(80),
            "note" to tx.note.take(500),
            "transactionDate" to tx.transactionDate,
            "transactionTime" to tx.transactionTime,
            "timestampMillis" to tx.timestampMillis,
            "referenceId" to tx.referenceId.take(120),
            "invoiceNumber" to tx.invoiceNumber.take(120),
            "entrySource" to tx.entrySource.name,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!existing.exists()) {
            payload["createdAt"] = FieldValue.serverTimestamp()
        }
        ref.set(payload, SetOptions.merge()).await()
    }

    suspend fun deleteTransaction(uid: String, txId: String) {
        firestore.collection("users").document(uid)
            .collection("transactions").document(txId)
            .delete()
            .await()
    }

    suspend fun fetchAllTransactions(uid: String): List<Transaction> {
        val snap = firestore.collection("users").document(uid)
            .collection("transactions")
            .whereEqualTo("uid", uid)
            .orderBy("timestampMillis", Query.Direction.ASCENDING)
            .get()
            .await()

        return snap.documents.mapNotNull { doc -> mapDocumentToTransaction(uid, doc) }
    }

    /**
     * Executes an indexed Firestore query pushing down uid, type, date range bounds,
     * and the most selective IN/equality constraint (category or paymentMethod) to Firestore,
     * then refines multi-dimension intersections and case-insensitive substring description matching locally.
     */
    suspend fun queryFilteredTransactions(
        uid: String,
        filter: TransactionFilter,
        source: Source = Source.DEFAULT
    ): List<Transaction> {
        var query: Query = firestore.collection("users").document(uid)
            .collection("transactions")
            .whereEqualTo("uid", uid)

        filter.transactionType?.let { txType ->
            query = query.whereEqualTo("type", txType.name)
        }

        // Firestore supports at most one whereIn per query; push down the most selective dimension
        val pushCategoriesToServer = filter.selectedCategories.isNotEmpty() &&
            filter.selectedCategories.size <= 10 &&
            (filter.selectedPaymentMethods.isEmpty() ||
                filter.selectedCategories.size <= filter.selectedPaymentMethods.size)

        if (pushCategoriesToServer) {
            val catNames = filter.selectedCategories.map { it.name }
            query = if (catNames.size == 1) {
                query.whereEqualTo("category", catNames.first())
            } else {
                query.whereIn("category", catNames)
            }
        } else if (filter.selectedPaymentMethods.isNotEmpty() && filter.selectedPaymentMethods.size <= 10) {
            val methods = filter.selectedPaymentMethods.toList()
            query = if (methods.size == 1) {
                query.whereEqualTo("paymentMethod", methods.first())
            } else {
                query.whereIn("paymentMethod", methods)
            }
        }

        filter.startDate?.takeIf { it.isNotBlank() }?.let { start ->
            val startMillis = calculator.toTimestampMillis(start, "00:00")
            query = query.whereGreaterThanOrEqualTo("timestampMillis", startMillis)
        }
        filter.endDate?.takeIf { it.isNotBlank() }?.let { end ->
            val endMillis = calculator.toTimestampMillis(end, "23:59") + 59_999L
            query = query.whereLessThanOrEqualTo("timestampMillis", endMillis)
        }

        query = query.orderBy("timestampMillis", Query.Direction.ASCENDING)

        val snap = query.get(source).await()
        val searchLower = filter.searchQuery.trim().lowercase()

        return snap.documents
            .mapNotNull { doc -> mapDocumentToTransaction(uid, doc) }
            .filter { tx ->
                val matchesCat = filter.selectedCategories.isEmpty() ||
                    tx.category in filter.selectedCategories
                val matchesMethod = filter.selectedPaymentMethods.isEmpty() ||
                    tx.paymentMethod in filter.selectedPaymentMethods
                val matchesSearch = searchLower.isBlank() ||
                    tx.description.lowercase().contains(searchLower) ||
                    tx.merchant.lowercase().contains(searchLower) ||
                    tx.customCategoryDetail.lowercase().contains(searchLower) ||
                    tx.note.lowercase().contains(searchLower) ||
                    tx.referenceId.lowercase().contains(searchLower)
                matchesCat && matchesMethod && matchesSearch
            }
    }

    private fun mapDocumentToTransaction(uid: String, doc: DocumentSnapshot): Transaction? {
        val id = doc.getString("id") ?: return null
        val typeStr = doc.getString("type") ?: return null
        val amount = doc.getDouble("amount") ?: return null
        val desc = doc.getString("description") ?: return null
        val catStr = doc.getString("category") ?: "Other"
        val date = doc.getString("transactionDate") ?: return null
        val time = doc.getString("transactionTime") ?: "00:00"
        val ts = doc.getLong("timestampMillis") ?: return null

        return Transaction(
            id = id,
            uid = uid,
            type = runCatching { TransactionType.valueOf(typeStr) }.getOrDefault(TransactionType.EXPENSE),
            amount = amount,
            description = desc,
            merchant = doc.getString("merchant") ?: "",
            category = runCatching { ExpenseCategory.valueOf(catStr) }.getOrDefault(ExpenseCategory.Other),
            customCategoryDetail = doc.getString("customCategoryDetail") ?: "",
            paymentMethod = doc.getString("paymentMethod") ?: "",
            sourceOfMoney = doc.getString("sourceOfMoney") ?: "",
            note = doc.getString("note") ?: "",
            transactionDate = date,
            transactionTime = time,
            timestampMillis = ts,
            referenceId = doc.getString("referenceId") ?: "",
            invoiceNumber = doc.getString("invoiceNumber") ?: "",
            entrySource = runCatching {
                EntrySource.valueOf(doc.getString("entrySource") ?: "CLOUD_RESTORE")
            }.getOrDefault(EntrySource.CLOUD_RESTORE),
            syncStatus = SyncStatus.SYNCED,
            createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: ts,
            updatedAtMillis = doc.getTimestamp("updatedAt")?.toDate()?.time ?: ts
        )
    }

    suspend fun markBackupTimestamp(uid: String) {
        firestore.collection("users").document(uid)
            .update(
                mapOf(
                    "lastBackupAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun saveFcmToken(uid: String, tokenId: String, token: String) {
        firestore.collection("users").document(uid)
            .collection("fcmTokens").document(tokenId)
            .set(
                mapOf(
                    "tokenId" to tokenId,
                    "uid" to uid,
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun upsertUserDirectory(
        uid: String,
        appUserCode: String,
        displayName: String,
        upiId: String,
        upiVerified: Boolean
    ) {
        firestore.collection("userDirectory").document(uid)
            .set(
                mapOf(
                    "uid" to uid,
                    "appUserCode" to appUserCode.take(32),
                    "displayName" to displayName.take(120),
                    "upiId" to upiId.take(100),
                    "upiVerified" to upiVerified,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun lookupUserByAppCode(appCode: String): Pair<String, String>? {
        val snap = firestore.collection("userDirectory")
            .whereEqualTo("appUserCode", appCode.trim().uppercase())
            .limit(1)
            .get()
            .await()
        val doc = snap.documents.firstOrNull() ?: return null
        return (doc.getString("uid") ?: doc.id) to (doc.getString("appUserCode") ?: appCode)
    }

    suspend fun createGroup(group: SplitGroup, creatorMember: GroupMember) {
        val batch = firestore.batch()
        batch.set(
            firestore.collection("groups").document(group.id),
            mapOf(
                "id" to group.id,
                "name" to group.name.take(100),
                "groupType" to group.groupType.displayName,
                "iconKey" to group.iconKey,
                "joinCode" to group.joinCode,
                "creatorUid" to group.creatorUid,
                "creatorName" to group.creatorName.take(120),
                "memberUids" to group.memberUids,
                "settlementDueDate" to group.settlementDueDate,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            firestore.collection("groupMembers").document(creatorMember.id),
            mapOf(
                "id" to creatorMember.id,
                "groupId" to creatorMember.groupId,
                "uid" to creatorMember.uid,
                "memberUids" to group.memberUids,
                "displayName" to creatorMember.displayName.take(120),
                "appUserCode" to creatorMember.appUserCode,
                "role" to creatorMember.role.name,
                "status" to creatorMember.status.name,
                "upiId" to creatorMember.upiId,
                "upiVerified" to creatorMember.upiVerified,
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            firestore.collection("groupJoinCodes").document(group.joinCode),
            mapOf(
                "joinCode" to group.joinCode,
                "groupId" to group.id,
                "groupName" to group.name.take(100),
                "groupType" to group.groupType.displayName,
                "creatorUid" to group.creatorUid,
                "creatorName" to group.creatorName.take(120),
                "active" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    suspend fun joinGroupByCode(joinCode: String, member: GroupMember): String {
        val code = joinCode.trim().uppercase()
        val codeSnap = firestore.collection("groupJoinCodes").document(code).get().await()
        require(codeSnap.exists()) { "Invalid group join code." }
        val groupId = codeSnap.getString("groupId") ?: error("Missing group reference.")
        val groupRef = firestore.collection("groups").document(groupId)
        val groupSnap = groupRef.get().await()
        val existingUids = (groupSnap.get("memberUids") as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val nextUids = (existingUids + member.uid).distinct()
        if (member.uid !in existingUids) {
            groupRef.update(
                mapOf(
                    "memberUids" to nextUids,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        firestore.collection("groupMembers").document("${groupId}_${member.uid}")
            .set(
                mapOf(
                    "id" to "${groupId}_${member.uid}",
                    "groupId" to groupId,
                    "uid" to member.uid,
                    "memberUids" to nextUids,
                    "displayName" to member.displayName.take(120),
                    "appUserCode" to member.appUserCode,
                    "role" to "MEMBER",
                    "status" to "ACTIVE",
                    "upiId" to member.upiId,
                    "upiVerified" to member.upiVerified,
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        return groupId
    }

    suspend fun leaveGroup(group: SplitGroup, uid: String) {
        val nextUids = group.memberUids.filterNot { it == uid }
        firestore.collection("groups").document(group.id)
            .update(
                mapOf(
                    "memberUids" to nextUids,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        firestore.collection("groupMembers").document("${group.id}_$uid")
            .update(
                mapOf(
                    "status" to "LEFT",
                    "memberUids" to nextUids,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun sendInvitation(inv: GroupInvitation) {
        firestore.collection("invitations").document(inv.id)
            .set(
                mapOf(
                    "id" to inv.id,
                    "groupId" to inv.groupId,
                    "groupName" to inv.groupName.take(100),
                    "groupType" to inv.groupType.displayName,
                    "inviterUid" to inv.inviterUid,
                    "inviterName" to inv.inviterName.take(120),
                    "inviteMethod" to inv.inviteMethod.name,
                    "targetUid" to inv.targetUid,
                    "targetAppCode" to inv.targetAppCode,
                    "targetEmail" to inv.targetEmail.trim().lowercase(),
                    "targetPhoneMasked" to inv.targetPhoneMasked,
                    "inviteToken" to inv.inviteToken,
                    "status" to "PENDING",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun respondToInvitation(inv: GroupInvitation, accept: Boolean, responderMember: GroupMember) {
        firestore.collection("invitations").document(inv.id)
            .update(
                mapOf(
                    "status" to if (accept) "ACCEPTED" else "REJECTED",
                    "targetUid" to responderMember.uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        if (accept) {
            val groupRef = firestore.collection("groups").document(inv.groupId)
            val snap = runCatching { groupRef.get().await() }.getOrNull()
            val existingUids = (snap?.get("memberUids") as? List<*>)
                ?.mapNotNull { it as? String }
                ?: listOf(inv.inviterUid)
            val nextUids = (existingUids + responderMember.uid).distinct()
            groupRef.update(
                mapOf(
                    "memberUids" to nextUids,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            firestore.collection("groupMembers").document("${inv.groupId}_${responderMember.uid}")
                .set(
                    mapOf(
                        "id" to "${inv.groupId}_${responderMember.uid}",
                        "groupId" to inv.groupId,
                        "uid" to responderMember.uid,
                        "memberUids" to nextUids,
                        "displayName" to responderMember.displayName.take(120),
                        "appUserCode" to responderMember.appUserCode,
                        "role" to "MEMBER",
                        "status" to "ACTIVE",
                        "upiId" to responderMember.upiId,
                        "upiVerified" to responderMember.upiVerified,
                        "joinedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
        }
    }

    suspend fun upsertGroupExpense(expense: GroupExpense) {
        val ref = firestore.collection("groupExpenses").document(expense.id)
        val existing = ref.get().await()
        val payload = hashMapOf<String, Any>(
            "id" to expense.id,
            "groupId" to expense.groupId,
            "memberUids" to expense.memberUids,
            "paidByUid" to expense.paidByUid,
            "paidByName" to expense.paidByName.take(120),
            "amount" to expense.amount,
            "description" to expense.description.take(200),
            "category" to expense.category.name,
            "customCategoryDetail" to expense.customCategoryDetail.take(120),
            "transactionDate" to expense.transactionDate,
            "transactionTime" to expense.transactionTime,
            "timestampMillis" to expense.timestampMillis,
            "splitType" to expense.splitType.name,
            "participantUids" to expense.participantUids,
            "shares" to expense.shares,
            "receiptReference" to expense.receiptReference.take(120),
            "note" to expense.note.take(500),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!existing.exists()) {
            payload["createdAt"] = FieldValue.serverTimestamp()
        }
        ref.set(payload, SetOptions.merge()).await()
    }

    suspend fun deleteGroupExpense(expenseId: String) {
        firestore.collection("groupExpenses").document(expenseId).delete().await()
    }

    suspend fun createSettlement(settlement: Settlement, upiUri: String, txRef: String) {
        val batch = firestore.batch()
        batch.set(
            firestore.collection("settlements").document(settlement.id),
            mapOf(
                "id" to settlement.id,
                "groupId" to settlement.groupId,
                "groupName" to settlement.groupName.take(100),
                "memberUids" to settlement.memberUids,
                "payerUid" to settlement.payerUid,
                "payerName" to settlement.payerName.take(120),
                "receiverUid" to settlement.receiverUid,
                "receiverName" to settlement.receiverName.take(120),
                "receiverUpiId" to settlement.receiverUpiId.take(100),
                "amount" to settlement.amount,
                "dueDate" to settlement.dueDate.take(10),
                "reminderEnabled" to settlement.reminderEnabled,
                "status" to settlement.status.name,
                "paymentMethod" to settlement.paymentMethod.name,
                "paymentApp" to settlement.paymentApp.take(64),
                "paymentReference" to settlement.paymentReference.take(120),
                "idempotencyKey" to settlement.idempotencyKey,
                "payerConfirmedAtMillis" to settlement.payerConfirmedAtMillis,
                "receiverConfirmedAtMillis" to settlement.receiverConfirmedAtMillis,
                "note" to settlement.note.take(300),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            firestore.collection("paymentRequests").document("req_${settlement.id}"),
            mapOf(
                "id" to "req_${settlement.id}",
                "groupId" to settlement.groupId,
                "settlementId" to settlement.id,
                "memberUids" to settlement.memberUids,
                "requesterUid" to settlement.receiverUid,
                "requesterName" to settlement.receiverName.take(120),
                "requesterUpiId" to settlement.receiverUpiId.take(100),
                "payerUid" to settlement.payerUid,
                "payerName" to settlement.payerName.take(120),
                "amount" to settlement.amount,
                "description" to settlement.note.take(200),
                "upiUri" to upiUri.take(500),
                "transactionRef" to txRef.take(64),
                "dueDate" to settlement.dueDate.take(10),
                "status" to settlement.status.name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    suspend fun updateSettlement(settlement: Settlement) {
        firestore.collection("settlements").document(settlement.id)
            .update(
                mapOf(
                    "status" to settlement.status.name,
                    "paymentMethod" to settlement.paymentMethod.name,
                    "paymentApp" to settlement.paymentApp.take(64),
                    "paymentReference" to settlement.paymentReference.take(120),
                    "payerConfirmedAtMillis" to settlement.payerConfirmedAtMillis,
                    "receiverConfirmedAtMillis" to settlement.receiverConfirmedAtMillis,
                    "note" to settlement.note.take(300),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun fetchSplitWorkspace(uid: String, email: String): RemoteSplitWorkspace {
        val groupsSnap = firestore.collection("groups").whereArrayContains("memberUids", uid).get().await()
        val membersSnap = firestore.collection("groupMembers").whereArrayContains("memberUids", uid).get().await()
        val expensesSnap = firestore.collection("groupExpenses").whereArrayContains("memberUids", uid).get().await()
        val settlementsSnap = firestore.collection("settlements").whereArrayContains("memberUids", uid).get().await()
        val invTargetSnap = firestore.collection("invitations").whereEqualTo("targetUid", uid).get().await()
        val invSentSnap = firestore.collection("invitations").whereEqualTo("inviterUid", uid).get().await()
        val invEmailDocs = if (email.isNotBlank()) {
            firestore.collection("invitations").whereEqualTo("targetEmail", email.trim().lowercase()).get().await().documents
        } else {
            emptyList()
        }

        val groups = groupsSnap.documents.mapNotNull { d ->
            SplitGroup(
                id = d.getString("id") ?: d.id,
                name = d.getString("name") ?: return@mapNotNull null,
                groupType = GroupType.fromFirestore(d.getString("groupType") ?: "Roommates"),
                iconKey = d.getString("iconKey") ?: "users",
                joinCode = d.getString("joinCode") ?: "",
                creatorUid = d.getString("creatorUid") ?: "",
                creatorName = d.getString("creatorName") ?: "",
                memberUids = (d.get("memberUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                settlementDueDate = d.getString("settlementDueDate") ?: "",
                createdAtMillis = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
            )
        }

        val members = membersSnap.documents.mapNotNull { d ->
            GroupMember(
                id = d.getString("id") ?: d.id,
                groupId = d.getString("groupId") ?: return@mapNotNull null,
                uid = d.getString("uid") ?: return@mapNotNull null,
                displayName = d.getString("displayName") ?: "Member",
                appUserCode = d.getString("appUserCode") ?: "",
                role = runCatching { GroupMemberRole.valueOf(d.getString("role") ?: "MEMBER") }.getOrDefault(GroupMemberRole.MEMBER),
                status = runCatching { GroupMemberStatus.valueOf(d.getString("status") ?: "ACTIVE") }.getOrDefault(GroupMemberStatus.ACTIVE),
                upiId = d.getString("upiId") ?: "",
                upiVerified = d.getBoolean("upiVerified") ?: false,
                joinedAtMillis = d.getTimestamp("joinedAt")?.toDate()?.time ?: System.currentTimeMillis(),
                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
            )
        }

        val expenses = expensesSnap.documents.mapNotNull { d ->
            @Suppress("UNCHECKED_CAST")
            val rawShares = (d.get("shares") as? Map<String, Any?>).orEmpty()
            val shares = rawShares.mapValues { (_, v) -> (v as? Number)?.toDouble() ?: 0.0 }
            GroupExpense(
                id = d.getString("id") ?: d.id,
                groupId = d.getString("groupId") ?: return@mapNotNull null,
                memberUids = (d.get("memberUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                paidByUid = d.getString("paidByUid") ?: return@mapNotNull null,
                paidByName = d.getString("paidByName") ?: "Member",
                amount = d.getDouble("amount") ?: 0.0,
                description = d.getString("description") ?: "",
                category = runCatching { ExpenseCategory.valueOf(d.getString("category") ?: "Other") }.getOrDefault(ExpenseCategory.Other),
                customCategoryDetail = d.getString("customCategoryDetail") ?: "",
                transactionDate = d.getString("transactionDate") ?: "",
                transactionTime = d.getString("transactionTime") ?: "12:00",
                timestampMillis = d.getLong("timestampMillis") ?: System.currentTimeMillis(),
                splitType = runCatching { SplitType.valueOf(d.getString("splitType") ?: "EQUAL") }.getOrDefault(SplitType.EQUAL),
                participantUids = (d.get("participantUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                shares = shares,
                receiptReference = d.getString("receiptReference") ?: "",
                note = d.getString("note") ?: "",
                syncStatus = SyncStatus.SYNCED,
                createdAtMillis = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
            )
        }

        val settlements = settlementsSnap.documents.mapNotNull { d ->
            Settlement(
                id = d.getString("id") ?: d.id,
                groupId = d.getString("groupId") ?: return@mapNotNull null,
                groupName = d.getString("groupName") ?: "",
                memberUids = (d.get("memberUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                payerUid = d.getString("payerUid") ?: return@mapNotNull null,
                payerName = d.getString("payerName") ?: "",
                receiverUid = d.getString("receiverUid") ?: return@mapNotNull null,
                receiverName = d.getString("receiverName") ?: "",
                receiverUpiId = d.getString("receiverUpiId") ?: "",
                amount = d.getDouble("amount") ?: 0.0,
                dueDate = d.getString("dueDate") ?: "",
                reminderEnabled = d.getBoolean("reminderEnabled") ?: true,
                status = runCatching { SettlementStatus.valueOf(d.getString("status") ?: "PENDING") }.getOrDefault(SettlementStatus.PENDING),
                paymentMethod = runCatching { SettlementPaymentMethod.valueOf(d.getString("paymentMethod") ?: "UPI") }.getOrDefault(SettlementPaymentMethod.UPI),
                paymentApp = d.getString("paymentApp") ?: "",
                paymentReference = d.getString("paymentReference") ?: "",
                idempotencyKey = d.getString("idempotencyKey") ?: "",
                payerConfirmedAtMillis = d.getLong("payerConfirmedAtMillis"),
                receiverConfirmedAtMillis = d.getLong("receiverConfirmedAtMillis"),
                note = d.getString("note") ?: "",
                createdAtMillis = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
            )
        }

        val invMap = linkedMapOf<String, GroupInvitation>()
        for (d in invTargetSnap.documents + invSentSnap.documents + invEmailDocs) {
            invMap[d.id] = GroupInvitation(
                id = d.getString("id") ?: d.id,
                groupId = d.getString("groupId") ?: continue,
                groupName = d.getString("groupName") ?: "",
                groupType = GroupType.fromFirestore(d.getString("groupType") ?: "Roommates"),
                inviterUid = d.getString("inviterUid") ?: "",
                inviterName = d.getString("inviterName") ?: "",
                inviteMethod = runCatching { InviteMethod.valueOf(d.getString("inviteMethod") ?: "IN_APP") }.getOrDefault(InviteMethod.IN_APP),
                targetUid = d.getString("targetUid") ?: "",
                targetAppCode = d.getString("targetAppCode") ?: "",
                targetEmail = d.getString("targetEmail") ?: "",
                targetPhoneMasked = d.getString("targetPhoneMasked") ?: "",
                inviteToken = d.getString("inviteToken") ?: "",
                status = runCatching { InvitationStatus.valueOf(d.getString("status") ?: "PENDING") }.getOrDefault(InvitationStatus.PENDING),
                createdAtMillis = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
            )
        }

        return RemoteSplitWorkspace(
            groups = groups,
            members = members,
            invitations = invMap.values.toList(),
            expenses = expenses,
            settlements = settlements
        )
    }
}

