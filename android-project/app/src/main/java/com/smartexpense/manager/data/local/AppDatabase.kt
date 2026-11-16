package com.smartexpense.manager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        UserProfileEntity::class,
        BudgetAlertStateEntity::class,
        SplitGroupEntity::class,
        GroupMemberEntity::class,
        GroupInvitationEntity::class,
        GroupExpenseEntity::class,
        SettlementEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun budgetAlertDao(): BudgetAlertDao
    abstract fun splitExpenseDao(): SplitExpenseDao
}
