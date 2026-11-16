package com.smartexpense.manager.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.smartexpense.manager.data.local.AppDatabase
import com.smartexpense.manager.data.local.BudgetAlertDao
import com.smartexpense.manager.data.local.SplitExpenseDao
import com.smartexpense.manager.data.local.TransactionDao
import com.smartexpense.manager.data.local.UserProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN customCategoryDetail TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_profile ADD COLUMN appUserCode TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE user_profile ADD COLUMN upiId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE user_profile ADD COLUMN upiVerified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS split_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, groupType TEXT NOT NULL, iconKey TEXT NOT NULL, joinCode TEXT NOT NULL, creatorUid TEXT NOT NULL, creatorName TEXT NOT NULL, memberUidsJson TEXT NOT NULL, settlementDueDate TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS group_members (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, uid TEXT NOT NULL, displayName TEXT NOT NULL, appUserCode TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, upiId TEXT NOT NULL, upiVerified INTEGER NOT NULL, joinedAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members(groupId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS group_invitations (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, groupName TEXT NOT NULL, groupType TEXT NOT NULL, inviterUid TEXT NOT NULL, inviterName TEXT NOT NULL, inviteMethod TEXT NOT NULL, targetUid TEXT NOT NULL, targetAppCode TEXT NOT NULL, targetEmail TEXT NOT NULL, targetPhoneMasked TEXT NOT NULL, inviteToken TEXT NOT NULL, status TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS group_expenses (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, memberUidsJson TEXT NOT NULL, paidByUid TEXT NOT NULL, paidByName TEXT NOT NULL, amount REAL NOT NULL, description TEXT NOT NULL, category TEXT NOT NULL, customCategoryDetail TEXT NOT NULL, transactionDate TEXT NOT NULL, transactionTime TEXT NOT NULL, timestampMillis INTEGER NOT NULL, splitType TEXT NOT NULL, participantUidsJson TEXT NOT NULL, sharesJson TEXT NOT NULL, receiptReference TEXT NOT NULL, note TEXT NOT NULL, deleted INTEGER NOT NULL, syncStatus TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_timestampMillis ON group_expenses(groupId, timestampMillis)")
            db.execSQL("CREATE TABLE IF NOT EXISTS settlements (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, groupName TEXT NOT NULL, memberUidsJson TEXT NOT NULL, payerUid TEXT NOT NULL, payerName TEXT NOT NULL, receiverUid TEXT NOT NULL, receiverName TEXT NOT NULL, receiverUpiId TEXT NOT NULL, amount REAL NOT NULL, dueDate TEXT NOT NULL, reminderEnabled INTEGER NOT NULL, status TEXT NOT NULL, paymentMethod TEXT NOT NULL, paymentApp TEXT NOT NULL, paymentReference TEXT NOT NULL, idempotencyKey TEXT NOT NULL, payerConfirmedAtMillis INTEGER, receiverConfirmedAtMillis INTEGER, note TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_settlements_groupId_status ON settlements(groupId, status)")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS upi_connections")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "smart_expense_db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideBudgetAlertDao(db: AppDatabase): BudgetAlertDao = db.budgetAlertDao()
    @Provides fun provideSplitExpenseDao(db: AppDatabase): SplitExpenseDao = db.splitExpenseDao()

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        return FirebaseFirestore.getInstance("ai-studio-8a2eb174-3722-4159-a37b-676cca223024").apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }
}
