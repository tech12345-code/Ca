import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  QueryConstraint,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from 'firebase/firestore';
import { db, handleFirestoreError, OperationType } from './firebase';
import { toTimestampMillis } from './ledgerEngine';
import {
  NotificationPreferences,
  Transaction,
  TransactionFilter,
  UserProfile,
} from '../types/expense';

export const DEFAULT_PREFERENCES: NotificationPreferences = {
  expenseReminders: true,
  reminderHour: 20,
  reminderMinute: 30,
  transactionConfirmations: true,
  budgetAlerts: true,
  dailySummary: false,
  weeklyReport: true,
  cloudBackupAlerts: true,
  quietHoursEnabled: true,
  quietStartHour: 22,
  quietEndHour: 7,
};

const localKey = (uid: string, suffix: string) => `sem_v1_${uid}_${suffix}`;

export function loadLocalProfile(uid: string): UserProfile | null {
  try {
    const raw = localStorage.getItem(localKey(uid, 'profile'));
    return raw ? (JSON.parse(raw) as UserProfile) : null;
  } catch {
    return null;
  }
}

export function saveLocalProfile(profile: UserProfile): void {
  localStorage.setItem(localKey(profile.uid, 'profile'), JSON.stringify(profile));
}

export function loadLocalTransactions(uid: string): Transaction[] {
  try {
    const raw = localStorage.getItem(localKey(uid, 'transactions'));
    return raw ? (JSON.parse(raw) as Transaction[]) : [];
  } catch {
    return [];
  }
}

export function saveLocalTransactions(uid: string, txs: Transaction[]): void {
  localStorage.setItem(localKey(uid, 'transactions'), JSON.stringify(txs));
}

export function loadTriggeredAlerts(uid: string): string[] {
  try {
    const raw = localStorage.getItem(localKey(uid, 'alerts'));
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}

export function markAlertTriggeredLocally(uid: string, alertKey: string): void {
  const existing = loadTriggeredAlerts(uid);
  if (!existing.includes(alertKey)) {
    localStorage.setItem(localKey(uid, 'alerts'), JSON.stringify([...existing, alertKey]));
  }
}

export async function syncProfileToFirestore(profile: UserProfile): Promise<void> {
  const path = `users/${profile.uid}`;
  const ref = doc(db, 'users', profile.uid);
  try {
    const snap = await getDoc(ref);
    if (!snap.exists()) {
      await setDoc(ref, {
        uid: profile.uid,
        displayName: profile.displayName.slice(0, 120) || 'User',
        email: profile.email.slice(0, 200),
        appUserCode: (profile.appUserCode || '').slice(0, 32),
        upiId: (profile.upiId || '').slice(0, 100),
        upiVerified: Boolean(profile.upiVerified),
        openingBalance: Number(profile.openingBalance) || 0,
        monthlyBudget: Number(profile.monthlyBudget) || 0,
        currencySymbol: profile.currencySymbol.slice(0, 8) || '₹',
        setupCompleted: Boolean(profile.setupCompleted),
        notifExpenseReminders: profile.preferences.expenseReminders,
        notifReminderHour: profile.preferences.reminderHour,
        notifReminderMinute: profile.preferences.reminderMinute,
        notifTransactionConfirmations: profile.preferences.transactionConfirmations,
        notifBudgetAlerts: profile.preferences.budgetAlerts,
        notifDailySummary: profile.preferences.dailySummary,
        notifWeeklyReport: profile.preferences.weeklyReport,
        notifCloudBackup: profile.preferences.cloudBackupAlerts,
        quietHoursEnabled: profile.preferences.quietHoursEnabled,
        quietStartHour: profile.preferences.quietStartHour,
        quietEndHour: profile.preferences.quietEndHour,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    } else {
      await updateDoc(ref, {
        displayName: profile.displayName.slice(0, 120) || 'User',
        email: profile.email.slice(0, 200),
        appUserCode: (profile.appUserCode || '').slice(0, 32),
        upiId: (profile.upiId || '').slice(0, 100),
        upiVerified: Boolean(profile.upiVerified),
        openingBalance: Number(profile.openingBalance) || 0,
        monthlyBudget: Number(profile.monthlyBudget) || 0,
        currencySymbol: profile.currencySymbol.slice(0, 8) || '₹',
        setupCompleted: Boolean(profile.setupCompleted),
        updatedAt: serverTimestamp(),
      });
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, path);
  }
}

export async function syncPreferencesToFirestore(
  uid: string,
  prefs: NotificationPreferences
): Promise<void> {
  const path = `users/${uid}`;
  const ref = doc(db, 'users', uid);
  try {
    await updateDoc(ref, {
      notifExpenseReminders: prefs.expenseReminders,
      notifReminderHour: prefs.reminderHour,
      notifReminderMinute: prefs.reminderMinute,
      notifTransactionConfirmations: prefs.transactionConfirmations,
      notifBudgetAlerts: prefs.budgetAlerts,
      notifDailySummary: prefs.dailySummary,
      notifWeeklyReport: prefs.weeklyReport,
      notifCloudBackup: prefs.cloudBackupAlerts,
      quietHoursEnabled: prefs.quietHoursEnabled,
      quietStartHour: prefs.quietStartHour,
      quietEndHour: prefs.quietEndHour,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, path);
  }
}

export async function syncTransactionToFirestore(
  tx: Transaction,
  isNew: boolean
): Promise<void> {
  const path = `users/${tx.uid}/transactions/${tx.id}`;
  const ref = doc(db, 'users', tx.uid, 'transactions', tx.id);
  try {
    const basePayload = {
      id: tx.id,
      uid: tx.uid,
      type: tx.type,
      amount: Number(tx.amount),
      description: tx.description.slice(0, 200),
      merchant: (tx.merchant || '').slice(0, 120),
      category: tx.category,
      customCategoryDetail: (tx.customCategoryDetail || '').slice(0, 120),
      paymentMethod: (tx.paymentMethod || '').slice(0, 64),
      sourceOfMoney: (tx.sourceOfMoney || '').slice(0, 80),
      note: (tx.note || '').slice(0, 500),
      transactionDate: tx.transactionDate,
      transactionTime: tx.transactionTime,
      timestampMillis: Math.floor(tx.timestampMillis),
      referenceId: (tx.referenceId || '').slice(0, 120),
      invoiceNumber: (tx.invoiceNumber || '').slice(0, 120),
      entrySource: tx.entrySource,
      updatedAt: serverTimestamp(),
    };

    if (isNew) {
      await setDoc(ref, {
        ...basePayload,
        createdAt: serverTimestamp(),
      });
    } else {
      const existing = await getDoc(ref);
      if (!existing.exists()) {
        await setDoc(ref, {
          ...basePayload,
          createdAt: serverTimestamp(),
        });
      } else {
        await updateDoc(ref, {
          type: basePayload.type,
          amount: basePayload.amount,
          description: basePayload.description,
          merchant: basePayload.merchant,
          category: basePayload.category,
          customCategoryDetail: basePayload.customCategoryDetail,
          paymentMethod: basePayload.paymentMethod,
          sourceOfMoney: basePayload.sourceOfMoney,
          note: basePayload.note,
          transactionDate: basePayload.transactionDate,
          transactionTime: basePayload.transactionTime,
          timestampMillis: basePayload.timestampMillis,
          referenceId: basePayload.referenceId,
          invoiceNumber: basePayload.invoiceNumber,
          entrySource: basePayload.entrySource,
          updatedAt: serverTimestamp(),
        });
      }
    }
  } catch (err) {
    handleFirestoreError(err, isNew ? OperationType.CREATE : OperationType.UPDATE, path);
  }
}

export async function deleteTransactionFromFirestore(
  uid: string,
  txId: string
): Promise<void> {
  const path = `users/${uid}/transactions/${txId}`;
  try {
    await deleteDoc(doc(db, 'users', uid, 'transactions', txId));
  } catch (err) {
    handleFirestoreError(err, OperationType.DELETE, path);
  }
}

export async function restoreUserCloudData(uid: string): Promise<{
  profile: UserProfile | null;
  transactions: Transaction[];
}> {
  const userPath = `users/${uid}`;
  try {
    const userSnap = await getDoc(doc(db, 'users', uid));
    let profile: UserProfile | null = null;
    if (userSnap.exists()) {
      const d = userSnap.data();
      profile = {
        uid: String(d.uid || uid),
        displayName: String(d.displayName || 'User'),
        email: String(d.email || ''),
        appUserCode: d.appUserCode ? String(d.appUserCode) : undefined,
        upiId: d.upiId ? String(d.upiId) : undefined,
        upiVerified: Boolean(d.upiVerified),
        openingBalance: Number(d.openingBalance || 0),
        monthlyBudget: Number(d.monthlyBudget || 0),
        currencySymbol: String(d.currencySymbol || '₹'),
        setupCompleted: Boolean(d.setupCompleted),
        lastBackupAt: d.lastBackupAt?.toDate ? d.lastBackupAt.toDate().toISOString() : null,
        preferences: {
          expenseReminders: d.notifExpenseReminders ?? true,
          reminderHour: Number(d.notifReminderHour ?? 20),
          reminderMinute: Number(d.notifReminderMinute ?? 30),
          transactionConfirmations: d.notifTransactionConfirmations ?? true,
          budgetAlerts: d.notifBudgetAlerts ?? true,
          dailySummary: d.notifDailySummary ?? false,
          weeklyReport: d.notifWeeklyReport ?? true,
          cloudBackupAlerts: d.notifCloudBackup ?? true,
          quietHoursEnabled: d.quietHoursEnabled ?? true,
          quietStartHour: Number(d.quietStartHour ?? 22),
          quietEndHour: Number(d.quietEndHour ?? 7),
        },
      };
    }

    const txQuery = query(
      collection(db, 'users', uid, 'transactions'),
      where('uid', '==', uid)
    );
    const txSnap = await getDocs(txQuery);
    const transactions: Transaction[] = txSnap.docs.map((docSnap) => {
      const d = docSnap.data();
      return {
        id: String(d.id || docSnap.id),
        uid: String(d.uid || uid),
        type: d.type === 'INCOME' ? 'INCOME' : 'EXPENSE',
        amount: Number(d.amount || 0),
        description: String(d.description || ''),
        merchant: String(d.merchant || ''),
        category: d.category || 'Other',
        customCategoryDetail: String(d.customCategoryDetail || ''),
        paymentMethod: String(d.paymentMethod || ''),
        sourceOfMoney: String(d.sourceOfMoney || ''),
        note: String(d.note || ''),
        transactionDate: String(d.transactionDate || new Date().toISOString().slice(0, 10)),
        transactionTime: String(d.transactionTime || '00:00'),
        timestampMillis: Number(d.timestampMillis || Date.now()),
        referenceId: String(d.referenceId || ''),
        invoiceNumber: String(d.invoiceNumber || ''),
        entrySource: d.entrySource || 'CLOUD_RESTORE',
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Number(d.timestampMillis || Date.now()),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Number(d.timestampMillis || Date.now()),
        pendingSync: false,
      };
    });

    return { profile, transactions };
  } catch (err) {
    handleFirestoreError(err, OperationType.GET, userPath);
  }
}

export async function backupAllToFirestore(
  profile: UserProfile,
  transactions: Transaction[]
): Promise<void> {
  await syncProfileToFirestore(profile);
  for (const tx of transactions) {
    await syncTransactionToFirestore(tx, false);
  }
  await updateDoc(doc(db, 'users', profile.uid), {
    lastBackupAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
}

export async function recordBudgetAlertInFirestore(
  uid: string,
  monthKey: string,
  thresholdPercent: number
): Promise<void> {
  const alertKey = `${monthKey}_${thresholdPercent}`;
  const path = `users/${uid}/alertHistory/${alertKey}`;
  try {
    const ref = doc(db, 'users', uid, 'alertHistory', alertKey);
    const existing = await getDoc(ref);
    if (!existing.exists()) {
      await setDoc(ref, {
        alertKey,
        uid,
        monthKey,
        thresholdPercent,
        createdAt: serverTimestamp(),
      });
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, path);
  }
}

export async function queryFilteredTransactionsFromFirestore(
  uid: string,
  filter: TransactionFilter
): Promise<Transaction[]> {
  const path = `users/${uid}/transactions`;
  try {
    const constraints: QueryConstraint[] = [where('uid', '==', uid)];

    if (filter.type !== 'ALL') {
      constraints.push(where('type', '==', filter.type));
    }

    let pushedCategories = false;
    let pushedPaymentMethods = false;

    const canPushCategories =
      filter.categories.length > 0 && filter.categories.length <= 10;
    const canPushMethods =
      filter.paymentMethods.length > 0 && filter.paymentMethods.length <= 10;

    if (
      canPushCategories &&
      (!canPushMethods || filter.categories.length <= filter.paymentMethods.length)
    ) {
      if (filter.categories.length === 1) {
        constraints.push(where('category', '==', filter.categories[0]));
      } else {
        constraints.push(where('category', 'in', filter.categories));
      }
      pushedCategories = true;
    } else if (canPushMethods) {
      if (filter.paymentMethods.length === 1) {
        constraints.push(where('paymentMethod', '==', filter.paymentMethods[0]));
      } else {
        constraints.push(where('paymentMethod', 'in', filter.paymentMethods));
      }
      pushedPaymentMethods = true;
    }

    if (filter.startDate) {
      constraints.push(
        where('timestampMillis', '>=', toTimestampMillis(filter.startDate, '00:00'))
      );
    }
    if (filter.endDate) {
      constraints.push(
        where('timestampMillis', '<=', toTimestampMillis(filter.endDate, '23:59') + 59999)
      );
    }

    constraints.push(orderBy('timestampMillis', 'asc'));

    const snap = await getDocs(
      query(collection(db, 'users', uid, 'transactions'), ...constraints)
    );

    const catSet = new Set(filter.categories);
    const methodSet = new Set(filter.paymentMethods);
    const q = filter.searchQuery.trim().toLowerCase();

    return snap.docs
      .map((docSnap) => {
        const d = docSnap.data();
        return {
          id: String(d.id || docSnap.id),
          uid: String(d.uid || uid),
          type: d.type === 'INCOME' ? 'INCOME' : 'EXPENSE',
          amount: Number(d.amount || 0),
          description: String(d.description || ''),
          merchant: String(d.merchant || ''),
          category: d.category || 'Other',
          customCategoryDetail: String(d.customCategoryDetail || ''),
          paymentMethod: String(d.paymentMethod || ''),
          sourceOfMoney: String(d.sourceOfMoney || ''),
          note: String(d.note || ''),
          transactionDate: String(
            d.transactionDate || new Date().toISOString().slice(0, 10)
          ),
          transactionTime: String(d.transactionTime || '00:00'),
          timestampMillis: Number(d.timestampMillis || Date.now()),
          referenceId: String(d.referenceId || ''),
          invoiceNumber: String(d.invoiceNumber || ''),
          entrySource: d.entrySource || 'CLOUD_RESTORE',
          createdAtMillis: d.createdAt?.toDate
            ? d.createdAt.toDate().getTime()
            : Number(d.timestampMillis || Date.now()),
          updatedAtMillis: d.updatedAt?.toDate
            ? d.updatedAt.toDate().getTime()
            : Number(d.timestampMillis || Date.now()),
          pendingSync: false,
        } as Transaction;
      })
      .filter((tx) => {
        if (!pushedCategories && catSet.size > 0 && !catSet.has(tx.category)) {
          return false;
        }
        if (
          !pushedPaymentMethods &&
          methodSet.size > 0 &&
          !methodSet.has(tx.paymentMethod)
        ) {
          return false;
        }
        if (q) {
          const hay =
            `${tx.description} ${tx.merchant} ${tx.customCategoryDetail || ''} ${tx.note} ${tx.referenceId} ${tx.paymentMethod} ${tx.sourceOfMoney}`.toLowerCase();
          if (!hay.includes(q)) return false;
        }
        return true;
      });
  } catch (err) {
    handleFirestoreError(err, OperationType.LIST, path);
  }
}
