package com.smartexpense.manager.data.repository

import com.smartexpense.manager.data.local.GroupExpenseEntity
import com.smartexpense.manager.data.local.GroupInvitationEntity
import com.smartexpense.manager.data.local.GroupMemberEntity
import com.smartexpense.manager.data.local.SettlementEntity
import com.smartexpense.manager.data.local.SplitExpenseDao
import com.smartexpense.manager.data.local.SplitGroupEntity
import com.smartexpense.manager.data.remote.FirestoreSyncService
import com.smartexpense.manager.domain.calculator.SettlementAction
import com.smartexpense.manager.domain.calculator.SplitExpenseCalculator
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberRole
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.GroupType
import com.smartexpense.manager.domain.model.InviteMethod
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementPaymentMethod
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SyncStatus
import com.smartexpense.manager.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SplitExpenseRepository @Inject constructor(
    private val splitDao: SplitExpenseDao,
    private val firestoreSync: FirestoreSyncService,
    private val splitCalculator: SplitExpenseCalculator
) {
    fun observeGroups(): Flow<List<SplitGroup>> =
        splitDao.observeAllGroups().map { list -> list.map { it.toDomain() } }

    fun observeMembers(): Flow<List<GroupMember>> =
        splitDao.observeAllMembers().map { list -> list.map { it.toDomain() } }

    fun observeInvitations(): Flow<List<GroupInvitation>> =
        splitDao.observeAllInvitations().map { list -> list.map { it.toDomain() } }

    fun observeGroupExpenses(): Flow<List<GroupExpense>> =
        splitDao.observeAllGroupExpenses().map { list -> list.map { it.toDomain() } }

    fun observeSettlements(): Flow<List<Settlement>> =
        splitDao.observeAllSettlements().map { list -> list.map { it.toDomain() } }

    suspend fun syncSplitWorkspace(uid: String, email: String): Result<Unit> = runCatching {
        // Push any pending offline group expenses first
        val pending = splitDao.getPendingSyncGroupExpenses()
        for (item in pending) {
            if (item.syncStatus == SyncStatus.PENDING_DELETE.name || item.deleted) {
                runCatching {
                    firestoreSync.deleteGroupExpense(item.id)
                    splitDao.hardDeleteGroupExpense(item.id)
                }
            } else {
                runCatching {
                    firestoreSync.upsertGroupExpense(item.toDomain())
                    splitDao.upsertGroupExpense(item.copy(syncStatus = SyncStatus.SYNCED.name))
                }
            }
        }

        val remote = firestoreSync.fetchSplitWorkspace(uid, email)
        splitDao.upsertGroups(remote.groups.map { SplitGroupEntity.fromDomain(it) })
        splitDao.upsertMembers(remote.members.map { GroupMemberEntity.fromDomain(it) })
        splitDao.upsertInvitations(remote.invitations.map { GroupInvitationEntity.fromDomain(it) })
        splitDao.upsertGroupExpenses(remote.expenses.map { GroupExpenseEntity.fromDomain(it) })
        splitDao.upsertSettlements(remote.settlements.map { SettlementEntity.fromDomain(it) })
    }

    suspend fun createGroup(
        name: String,
        groupType: GroupType,
        dueDate: String,
        profile: UserProfile
    ): Result<SplitGroup> = runCatching {
        val now = System.currentTimeMillis()
        val id = "grp_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val appCode = profile.appUserCode.ifBlank { splitCalculator.generateAppUserCode(profile.uid) }
        val group = SplitGroup(
            id = id,
            name = name.trim(),
            groupType = groupType,
            iconKey = groupType.name.lowercase(),
            joinCode = splitCalculator.generateJoinCode(id),
            creatorUid = profile.uid,
            creatorName = profile.displayName,
            memberUids = listOf(profile.uid),
            settlementDueDate = dueDate,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val creatorMember = GroupMember(
            id = "${id}_${profile.uid}",
            groupId = id,
            uid = profile.uid,
            displayName = profile.displayName,
            appUserCode = appCode,
            role = GroupMemberRole.ADMIN,
            status = GroupMemberStatus.ACTIVE,
            upiId = profile.upiId,
            upiVerified = profile.upiVerified,
            joinedAtMillis = now,
            updatedAtMillis = now
        )
        splitDao.upsertGroup(SplitGroupEntity.fromDomain(group))
        splitDao.upsertMember(GroupMemberEntity.fromDomain(creatorMember))
        runCatching { firestoreSync.createGroup(group, creatorMember) }
        group
    }

    suspend fun joinGroupByCode(joinCode: String, profile: UserProfile): Result<String> = runCatching {
        val appCode = profile.appUserCode.ifBlank { splitCalculator.generateAppUserCode(profile.uid) }
        val tempMember = GroupMember(
            id = "temp_${profile.uid}",
            groupId = "",
            uid = profile.uid,
            displayName = profile.displayName,
            appUserCode = appCode,
            role = GroupMemberRole.MEMBER,
            status = GroupMemberStatus.ACTIVE,
            upiId = profile.upiId,
            upiVerified = profile.upiVerified,
            joinedAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        val groupId = firestoreSync.joinGroupByCode(joinCode, tempMember)
        syncSplitWorkspace(profile.uid, profile.email)
        groupId
    }

    suspend fun leaveGroup(
        group: SplitGroup,
        members: List<GroupMember>,
        expenses: List<GroupExpense>,
        settlements: List<Settlement>,
        uid: String
    ): Result<Unit> {
        val check = splitCalculator.leaveGroup(group, members, uid, expenses, settlements)
        if (check.isFailure) return Result.failure(check.exceptionOrNull()!!)
        val (_, updatedMembers) = check.getOrThrow()
        splitDao.deleteGroup(group.id)
        splitDao.upsertMembers(updatedMembers.map { GroupMemberEntity.fromDomain(it) })
        runCatching { firestoreSync.leaveGroup(group, uid) }
        return Result.success(Unit)
    }

    suspend fun sendInvitation(
        group: SplitGroup,
        profile: UserProfile,
        method: InviteMethod,
        targetInput: String
    ): Result<GroupInvitation> = runCatching {
        val now = System.currentTimeMillis()
        val token = "INV-${now.toString(36).uppercase().takeLast(6)}"
        var targetUid = ""
        var targetCode = ""
        var targetEmail = ""
        var targetPhoneMasked = ""

        when (method) {
            InviteMethod.APP_ID -> {
                val found = runCatching { firestoreSync.lookupUserByAppCode(targetInput) }.getOrNull()
                if (found != null) {
                    targetUid = found.first
                    targetCode = found.second
                } else {
                    targetCode = targetInput.trim().uppercase()
                }
            }
            InviteMethod.EMAIL -> targetEmail = targetInput.trim().lowercase()
            InviteMethod.SMS -> {
                val digits = targetInput.filter { it.isDigit() }
                targetPhoneMasked = if (digits.length >= 4) "••••••${digits.takeLast(4)}" else "****"
            }
            else -> Unit
        }

        val inv = GroupInvitation(
            id = "inv_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            groupId = group.id,
            groupName = group.name,
            groupType = group.groupType,
            inviterUid = profile.uid,
            inviterName = profile.displayName,
            inviteMethod = method,
            targetUid = targetUid,
            targetAppCode = targetCode,
            targetEmail = targetEmail,
            targetPhoneMasked = targetPhoneMasked,
            inviteToken = token,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        splitDao.upsertInvitation(GroupInvitationEntity.fromDomain(inv))
        runCatching { firestoreSync.sendInvitation(inv) }
        inv
    }

    suspend fun respondToInvitation(
        inv: GroupInvitation,
        accept: Boolean,
        profile: UserProfile
    ): Result<Unit> = runCatching {
        val updated = splitCalculator.respondToInvitation(inv, profile.uid, accept).getOrThrow()
        val appCode = profile.appUserCode.ifBlank { splitCalculator.generateAppUserCode(profile.uid) }
        val member = GroupMember(
            id = "${inv.groupId}_${profile.uid}",
            groupId = inv.groupId,
            uid = profile.uid,
            displayName = profile.displayName,
            appUserCode = appCode,
            role = GroupMemberRole.MEMBER,
            status = GroupMemberStatus.ACTIVE,
            upiId = profile.upiId,
            upiVerified = profile.upiVerified,
            joinedAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        splitDao.upsertInvitation(GroupInvitationEntity.fromDomain(updated))
        if (accept) {
            splitDao.upsertMember(GroupMemberEntity.fromDomain(member))
        }
        runCatching {
            firestoreSync.respondToInvitation(inv, accept, member)
            syncSplitWorkspace(profile.uid, profile.email)
        }
    }

    suspend fun saveGroupExpense(expense: GroupExpense): Result<Unit> = runCatching {
        val pending = expense.copy(syncStatus = SyncStatus.PENDING_UPLOAD)
        splitDao.upsertGroupExpense(GroupExpenseEntity.fromDomain(pending))
        runCatching {
            firestoreSync.upsertGroupExpense(pending)
            splitDao.upsertGroupExpense(GroupExpenseEntity.fromDomain(pending.copy(syncStatus = SyncStatus.SYNCED)))
        }
    }

    suspend fun deleteGroupExpense(expense: GroupExpense): Result<Unit> = runCatching {
        splitDao.upsertGroupExpense(
            GroupExpenseEntity.fromDomain(
                expense.copy(deleted = true, syncStatus = SyncStatus.PENDING_DELETE)
            )
        )
        runCatching {
            firestoreSync.deleteGroupExpense(expense.id)
            splitDao.hardDeleteGroupExpense(expense.id)
        }
    }

    suspend fun createSettlement(
        group: SplitGroup,
        payerUid: String,
        payerName: String,
        receiverUid: String,
        receiverName: String,
        receiverUpiId: String,
        amount: Double,
        dueDate: String,
        method: SettlementPaymentMethod
    ): Result<Settlement> = runCatching {
        val now = System.currentTimeMillis()
        val id = "settle_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val settlement = Settlement(
            id = id,
            groupId = group.id,
            groupName = group.name,
            memberUids = group.memberUids,
            payerUid = payerUid,
            payerName = payerName,
            receiverUid = receiverUid,
            receiverName = receiverName,
            receiverUpiId = receiverUpiId,
            amount = splitCalculator.round2(amount),
            dueDate = dueDate,
            reminderEnabled = true,
            status = SettlementStatus.PENDING,
            paymentMethod = method,
            idempotencyKey = "idem_${id}_$now",
            note = "${group.name} balance settlement",
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val trRef = "SEM${now.toString().takeLast(8)}${id.takeLast(4).uppercase()}"
        val uri = if (receiverUpiId.isNotBlank()) {
            splitCalculator.buildUpiPaymentUri(receiverUpiId, receiverName, amount, settlement.note, trRef)
        } else ""
        splitDao.upsertSettlement(SettlementEntity.fromDomain(settlement))
        runCatching { firestoreSync.createSettlement(settlement, uri, trRef) }
        settlement
    }

    suspend fun transitionSettlement(
        settlement: Settlement,
        allSettlements: List<Settlement>,
        actorUid: String,
        action: SettlementAction,
        paymentApp: String = "",
        paymentReference: String = "",
        method: SettlementPaymentMethod = settlement.paymentMethod
    ): Result<Settlement> {
        val res = splitCalculator.transitionSettlementStatus(
            settlement = settlement,
            existingSettlements = allSettlements,
            actorUid = actorUid,
            action = action,
            paymentApp = paymentApp,
            paymentReference = paymentReference,
            paymentMethod = method
        )
        if (res.isFailure) return res
        val updated = res.getOrThrow()
        splitDao.upsertSettlement(SettlementEntity.fromDomain(updated))
        runCatching { firestoreSync.updateSettlement(updated) }
        return Result.success(updated)
    }
}
