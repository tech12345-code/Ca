package com.smartexpense.manager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE uid = :uid AND syncStatus != 'PENDING_DELETE' ORDER BY timestampMillis ASC, createdAtMillis ASC")
    fun observeTransactions(uid: String): Flow<List<TransactionEntity>>

    @RawQuery(observedEntities = [TransactionEntity::class])
    fun observeFilteredTransactions(query: SupportSQLiteQuery): Flow<List<TransactionEntity>>

    @Query("SELECT DISTINCT paymentMethod FROM transactions WHERE uid = :uid AND syncStatus != 'PENDING_DELETE' AND paymentMethod != '' ORDER BY paymentMethod ASC")
    fun observeDistinctPaymentMethods(uid: String): Flow<List<String>>

    @Query("SELECT * FROM transactions WHERE uid = :uid AND syncStatus != 'PENDING_DELETE' ORDER BY timestampMillis ASC, createdAtMillis ASC")
    suspend fun getAllTransactions(uid: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE uid = :uid AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_DELETE')")
    suspend fun getPendingSyncTransactions(uid: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE uid = :uid AND referenceId = :referenceId AND syncStatus != 'PENDING_DELETE' LIMIT 1")
    suspend fun findByReferenceId(uid: String, referenceId: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    suspend fun deleteAllForUser(uid: String)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE uid = :uid LIMIT 1")
    fun observeProfile(uid: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE uid = :uid LIMIT 1")
    suspend fun getProfile(uid: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profile WHERE uid = :uid")
    suspend fun deleteProfile(uid: String)
}

@Dao
interface BudgetAlertDao {
    @Query("SELECT EXISTS(SELECT 1 FROM budget_alert_state WHERE alertKey = :alertKey AND uid = :uid)")
    suspend fun wasAlertTriggered(uid: String, alertKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markAlertTriggered(state: BudgetAlertStateEntity)
}

@Dao
interface SplitExpenseDao {
    @Query("SELECT * FROM split_groups ORDER BY updatedAtMillis DESC")
    fun observeAllGroups(): Flow<List<SplitGroupEntity>>

    @Query("SELECT * FROM group_members")
    fun observeAllMembers(): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_invitations ORDER BY updatedAtMillis DESC")
    fun observeAllInvitations(): Flow<List<GroupInvitationEntity>>

    @Query("SELECT * FROM group_expenses WHERE deleted = 0 AND syncStatus != 'PENDING_DELETE' ORDER BY timestampMillis DESC")
    fun observeAllGroupExpenses(): Flow<List<GroupExpenseEntity>>

    @Query("SELECT * FROM group_expenses WHERE syncStatus IN ('PENDING_UPLOAD', 'PENDING_DELETE')")
    suspend fun getPendingSyncGroupExpenses(): List<GroupExpenseEntity>

    @Query("SELECT * FROM settlements ORDER BY updatedAtMillis DESC")
    fun observeAllSettlements(): Flow<List<SettlementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: SplitGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<SplitGroupEntity>)

    @Query("DELETE FROM split_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvitation(inv: GroupInvitationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvitations(invs: List<GroupInvitationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupExpense(expense: GroupExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupExpenses(expenses: List<GroupExpenseEntity>)

    @Query("DELETE FROM group_expenses WHERE id = :expenseId")
    suspend fun hardDeleteGroupExpense(expenseId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettlement(settlement: SettlementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettlements(settlements: List<SettlementEntity>)
}

