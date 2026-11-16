import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  onAuthStateChanged,
  reauthenticateWithPopup,
  signInWithPopup,
  signOut,
} from 'firebase/auth';
import {
  Home,
  BookOpen,
  Users,
  ScanLine,
  BarChart3,
  Settings,
  Plus,
  Bell,
  X,
} from 'lucide-react';
import {
  AppRoute,
  AutoLockTimeoutSeconds,
  BalanceValidationResult,
  ExpenseCategory,
  Group,
  GroupExpense,
  GroupInvitation,
  GroupType,
  NotificationPreferences,
  PaymentRequest,
  PendingNotificationPrompt,
  QUICK_CONFIRM_CATEGORIES,
  SecurityMethod,
  SecuritySettings,
  Settlement,
  Transaction,
  TransactionType,
  UserProfile,
} from './types/expense';
import { auth, googleProvider, testFirestoreConnection } from './lib/firebase';
import {
  calculateBalanceSummary,
  findDuplicateTransaction,
  toTimestampMillis,
  validateTransactionBalance,
} from './lib/ledgerEngine';
import {
  generateAppUserCode,
  generateGroupJoinCode,
  leaveGroupMemberState,
  respondToInvitationState,
} from './lib/splitEngine';
import {
  SplitWorkspaceLocalState,
  createGroupInFirestore,
  createInvitationInFirestore,
  createSettlementAndRequestInFirestore,
  deleteGroupExpenseInFirestore,
  joinGroupByCodeInFirestore,
  leaveGroupInFirestore,
  loadLocalSplitState,
  respondInvitationInFirestore,
  restoreSplitWorkspaceFromFirestore,
  saveLocalSplitState,
  syncGroupExpenseToFirestore,
  syncUserDirectoryProfile,
  updateSettlementStatusInFirestore,
} from './lib/splitSync';
import {
  DEFAULT_PREFERENCES,
  backupAllToFirestore,
  deleteTransactionFromFirestore,
  loadLocalProfile,
  loadLocalTransactions,
  loadTriggeredAlerts,
  markAlertTriggeredLocally,
  recordBudgetAlertInFirestore,
  restoreUserCloudData,
  saveLocalProfile,
  saveLocalTransactions,
  syncPreferencesToFirestore,
  syncProfileToFirestore,
  syncTransactionToFirestore,
} from './lib/storageSync';
import {
  DEFAULT_SECURITY_SETTINGS,
  canDispatchDeepLink,
  changeExistingSemPin,
  createOrReplaceSemPinSettings,
  isLockActiveOnColdStart,
  loadSecuritySettings,
  registerPlatformBiometricCredential,
  resetForgottenPinAfterVerifiedIdentity,
  saveSecuritySettings,
  shouldLockOnResume,
  verifyAndAdvanceLockout,
  verifyPlatformBiometricCredential,
} from './lib/appLock';
import { OnboardingView } from './components/OnboardingView';
import { AppLockScreen } from './components/AppLockScreen';
import { SecuritySettingsView } from './components/SecuritySettingsView';
import { DashboardView } from './components/DashboardView';
import { TransactionModal } from './components/TransactionModal';
import { ReceiptImportView } from './components/ReceiptImportView';
import { LedgerView } from './components/LedgerView';
import { AnalyticsView } from './components/AnalyticsView';
import { PdfExportView } from './components/PdfExportView';
import { SettingsView } from './components/SettingsView';
import { SplitExpensesView } from './components/SplitExpensesView';
import {
  BalanceExhaustedModal,
  InsufficientBalanceModal,
} from './components/BalanceAlertModal';

const LOCAL_VAULT_UID = 'local_offline_vault';

export default function App() {
  const [authReady, setAuthReady] = useState(false);
  const [firebaseUid, setFirebaseUid] = useState<string | null>(null);
  const [firebaseUserMeta, setFirebaseUserMeta] = useState<{
    name: string;
    email: string;
  }>({ name: '', email: '' });
  const [useOfflineVault, setUseOfflineVault] = useState<boolean>(
    () => localStorage.getItem('sem_use_offline_vault') === '1'
  );
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);

  const activeUid = firebaseUid || (useOfflineVault ? LOCAL_VAULT_UID : null);

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [securitySettings, setSecuritySettings] = useState<SecuritySettings>(
    DEFAULT_SECURITY_SETTINGS
  );
  const [isAppUnlocked, setIsAppUnlocked] = useState(false);
  const lastBackgroundedRef = useRef<number | null>(null);
  const suppressBackgroundLockRef = useRef(false);

  const [splitState, setSplitState] = useState<SplitWorkspaceLocalState>({
    groups: [],
    membersByGroup: {},
    invitations: [],
    expenses: [],
    settlements: [],
    paymentRequests: [],
  });
  const [route, setRoute] = useState<AppRoute>('dashboard');

  const [modalState, setModalState] = useState<{
    open: boolean;
    type: TransactionType;
    presetCategory?: ExpenseCategory;
    editing?: Transaction | null;
  }>({ open: false, type: 'EXPENSE' });

  const [syncStatusMessage, setSyncStatusMessage] = useState<string | null>(null);
  const [notificationQueue, setNotificationQueue] = useState<PendingNotificationPrompt[]>([]);
  const [insufficientBalanceAlert, setInsufficientBalanceAlert] =
    useState<BalanceValidationResult | null>(null);
  const [balanceExhaustedAlert, setBalanceExhaustedAlert] = useState(false);

  // Test Firestore connection on boot
  useEffect(() => {
    testFirestoreConnection();
  }, []);

  const applyLoadedSecurity = useCallback((uid: string) => {
    const loaded = loadSecuritySettings(uid);
    setSecuritySettings(loaded);
    setIsAppUnlocked(!isLockActiveOnColdStart(loaded));
  }, []);

  const persistSecurity = useCallback(
    (next: SecuritySettings) => {
      setSecuritySettings(next);
      if (activeUid) {
        saveSecuritySettings(activeUid, next);
      }
    },
    [activeUid]
  );

  // Background auto-lock timeout tracking via visibilitychange / pagehide
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        if (!suppressBackgroundLockRef.current) {
          lastBackgroundedRef.current = Date.now();
        }
      } else if (document.visibilityState === 'visible') {
        if (suppressBackgroundLockRef.current) {
          suppressBackgroundLockRef.current = false;
          return;
        }
        if (
          lastBackgroundedRef.current !== null &&
          shouldLockOnResume(securitySettings, lastBackgroundedRef.current, Date.now())
        ) {
          setIsAppUnlocked(false);
        }
      }
    };
    const onPageHide = () => {
      if (securitySettings.appLockEnabled && securitySettings.lockOnAppClose) {
        lastBackgroundedRef.current = Date.now();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [securitySettings]);

  // Listen to Firebase Auth state
  useEffect(() => {
    const unsub = onAuthStateChanged(auth, async (user) => {
      if (user) {
        setFirebaseUid(user.uid);
        setFirebaseUserMeta({
          name: user.displayName || 'Account Holder',
          email: user.email || '',
        });
        setUseOfflineVault(false);
        localStorage.removeItem('sem_use_offline_vault');

        applyLoadedSecurity(user.uid);

        const cachedProfile = loadLocalProfile(user.uid);
        const cachedTxs = loadLocalTransactions(user.uid);
        const cachedSplits = loadLocalSplitState(user.uid);
        if (cachedProfile) setProfile(cachedProfile);
        setTransactions(cachedTxs);
        setSplitState(cachedSplits);

        try {
          const cloud = await restoreUserCloudData(user.uid);
          if (cloud.profile) {
            const enrichedProfile: UserProfile = {
              ...cloud.profile,
              appUserCode: cloud.profile.appUserCode || generateAppUserCode(user.uid),
            };
            setProfile(enrichedProfile);
            saveLocalProfile(enrichedProfile);
            syncUserDirectoryProfile({
              uid: user.uid,
              displayName: enrichedProfile.displayName,
              appUserCode: enrichedProfile.appUserCode || generateAppUserCode(user.uid),
              upiId: enrichedProfile.upiId || '',
              upiVerified: Boolean(enrichedProfile.upiVerified),
            }).catch(() => {});
          }
          if (cloud.transactions.length > 0 || !cachedTxs.length) {
            setTransactions(cloud.transactions);
            saveLocalTransactions(user.uid, cloud.transactions);
          }
          const syncedSplits = await restoreSplitWorkspaceFromFirestore(
            user.uid,
            user.emailVerified && user.email ? user.email : '',
            cachedSplits
          );
          setSplitState(syncedSplits);
          saveLocalSplitState(user.uid, syncedSplits);
        } catch {
          // Offline or first-time user without remote doc yet
        }
      } else {
        setFirebaseUid(null);
        setFirebaseUserMeta({ name: '', email: '' });
        if (localStorage.getItem('sem_use_offline_vault') === '1') {
          applyLoadedSecurity(LOCAL_VAULT_UID);
          setProfile(loadLocalProfile(LOCAL_VAULT_UID));
          setTransactions(loadLocalTransactions(LOCAL_VAULT_UID));
          setSplitState(loadLocalSplitState(LOCAL_VAULT_UID));
        } else {
          setProfile(null);
          setTransactions([]);
          setSecuritySettings(DEFAULT_SECURITY_SETTINGS);
          setIsAppUnlocked(false);
          setSplitState({
            groups: [],
            membersByGroup: {},
            invitations: [],
            expenses: [],
            settlements: [],
            paymentRequests: [],
          });
        }
      }
      setAuthReady(true);
    });
    return () => unsub();
  }, [applyLoadedSecurity]);

  // Deterministic Chronological Balance Summary
  const summary = useMemo(() => {
    return calculateBalanceSummary(
      profile?.openingBalance || 0,
      profile?.monthlyBudget || 0,
      transactions
    );
  }, [profile?.openingBalance, profile?.monthlyBudget, transactions]);

  const canDispatchNotifications = canDispatchDeepLink(securitySettings, isAppUnlocked);

  const pushToast = useCallback(
    (prompt: PendingNotificationPrompt) => {
      if (!canDispatchNotifications) return;
      setNotificationQueue((prev) => [prompt, ...prev].slice(0, 3));
      if (
        typeof Notification !== 'undefined' &&
        Notification.permission === 'granted' &&
        profile?.preferences.quietHoursEnabled !== undefined
      ) {
        const hour = new Date().getHours();
        const prefs = profile.preferences;
        const inQuiet =
          prefs.quietHoursEnabled &&
          (prefs.quietStartHour > prefs.quietEndHour
            ? hour >= prefs.quietStartHour || hour < prefs.quietEndHour
            : hour >= prefs.quietStartHour && hour < prefs.quietEndHour);
        if (!inQuiet) {
          new Notification(prompt.title, { body: prompt.body });
        }
      }
    },
    [canDispatchNotifications, profile]
  );

  // Deduplicated Budget Threshold Alerts (50%, 75%, 90%, 100%)
  const evaluateBudgetAlerts = useCallback(
    (nextTxs: Transaction[], currentProfile: UserProfile) => {
      if (!currentProfile.preferences.budgetAlerts || currentProfile.monthlyBudget <= 0) {
        return;
      }
      const nextSummary = calculateBalanceSummary(
        currentProfile.openingBalance,
        currentProfile.monthlyBudget,
        nextTxs
      );
      const monthKey = new Date().toISOString().slice(0, 7);
      const triggered = loadTriggeredAlerts(currentProfile.uid);
      const thresholds = [50, 75, 90, 100];

      for (const threshold of thresholds) {
        const alertKey = `${monthKey}_${threshold}`;
        if (nextSummary.budgetUsagePercent >= threshold && !triggered.includes(alertKey)) {
          markAlertTriggeredLocally(currentProfile.uid, alertKey);
          if (firebaseUid) {
            recordBudgetAlertInFirestore(firebaseUid, monthKey, threshold).catch(() => {});
          }
          pushToast({
            id: `budget_${alertKey}`,
            type: 'BUDGET_ALERT',
            title: `Budget Alert: ${threshold}% reached`,
            body: `You have used ${nextSummary.budgetUsagePercent.toFixed(1)}% of your monthly budget.`,
          });
        }
      }
    },
    [firebaseUid, pushToast]
  );

  const handleGoogleSignIn = async () => {
    setAuthLoading(true);
    setAuthError(null);
    try {
      await signInWithPopup(auth, googleProvider);
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : 'Google Sign-In failed');
    } finally {
      setAuthLoading(false);
    }
  };

  const handleUseLocalVault = () => {
    localStorage.setItem('sem_use_offline_vault', '1');
    setUseOfflineVault(true);
    applyLoadedSecurity(LOCAL_VAULT_UID);
    setProfile(loadLocalProfile(LOCAL_VAULT_UID));
    setTransactions(loadLocalTransactions(LOCAL_VAULT_UID));
    setSplitState(loadLocalSplitState(LOCAL_VAULT_UID));
  };

  const handleCompleteSetup = async (
    monthlyBudget: number,
    currencySymbol: string,
    displayName: string
  ) => {
    if (!activeUid) return;
    const appUserCode = generateAppUserCode(activeUid);
    const newProfile: UserProfile = {
      uid: activeUid,
      displayName,
      email: firebaseUserMeta.email,
      appUserCode,
      upiId: '',
      upiVerified: false,
      openingBalance: monthlyBudget,
      monthlyBudget,
      currencySymbol,
      setupCompleted: true,
      preferences: DEFAULT_PREFERENCES,
    };
    setProfile(newProfile);
    saveLocalProfile(newProfile);

    if (firebaseUid) {
      try {
        await syncProfileToFirestore(newProfile);
        await syncUserDirectoryProfile({
          uid: firebaseUid,
          displayName,
          appUserCode,
          upiId: '',
          upiVerified: false,
        });
      } catch (err) {
        console.error(err);
      }
    }
  };

  const handleCompleteSecuritySetup = async (params: {
    method: SecurityMethod;
    pin: string;
    confirmPin: string;
    enableFingerprintQuickUnlock: boolean;
  }): Promise<{ ok: boolean; error?: string }> => {
    if (!activeUid) return { ok: false, error: 'No active session.' };
    if (params.method === 'SEM_PIN') {
      let credId: string | undefined = securitySettings.webAuthnCredentialIdBase64;
      if (params.enableFingerprintQuickUnlock && !credId) {
        suppressBackgroundLockRef.current = true;
        const bioRes = await registerPlatformBiometricCredential(
          activeUid,
          profile?.displayName || 'User'
        );
        suppressBackgroundLockRef.current = false;
        if (bioRes.credentialIdBase64) credId = bioRes.credentialIdBase64;
      }
      const res = await createOrReplaceSemPinSettings(
        { ...securitySettings, webAuthnCredentialIdBase64: credId },
        params.pin,
        params.confirmPin,
        Boolean(params.enableFingerprintQuickUnlock && credId)
      );
      if (res.error || !res.settings) {
        return { ok: false, error: res.error || 'Failed to save SEM-PIN.' };
      }
      persistSecurity(res.settings);
      setIsAppUnlocked(true);
      return { ok: true };
    }

    if (params.method === 'DEVICE_LOCK') {
      suppressBackgroundLockRef.current = true;
      const enroll = await registerPlatformBiometricCredential(
        activeUid,
        profile?.displayName || 'User'
      );
      suppressBackgroundLockRef.current = false;
      if (enroll.error || !enroll.credentialIdBase64) {
        return { ok: false, error: enroll.error || 'Device lock enrollment failed.' };
      }
      const next: SecuritySettings = {
        ...securitySettings,
        securityConfigured: true,
        appLockEnabled: true,
        method: 'DEVICE_LOCK',
        fingerprintQuickUnlockEnabled: true,
        webAuthnCredentialIdBase64: enroll.credentialIdBase64,
        failedAttempts: 0,
        lockoutUntilMillis: 0,
      };
      persistSecurity(next);
      setIsAppUnlocked(true);
      return { ok: true };
    }

    const next: SecuritySettings = {
      ...securitySettings,
      securityConfigured: true,
      appLockEnabled: false,
      method: 'NONE',
      fingerprintQuickUnlockEnabled: false,
      failedAttempts: 0,
      lockoutUntilMillis: 0,
    };
    persistSecurity(next);
    setIsAppUnlocked(true);
    return { ok: true };
  };

  const handleVerifyPin = async (
    pinAttempt: string
  ): Promise<{ ok: boolean; error?: string }> => {
    const res = await verifyAndAdvanceLockout(securitySettings, pinAttempt);
    persistSecurity(res.updated);
    if (res.ok) {
      setIsAppUnlocked(true);
      return { ok: true };
    }
    return { ok: false, error: res.error };
  };

  const handleTriggerDeviceUnlock = async (): Promise<{ ok: boolean; error?: string }> => {
    if (!securitySettings.webAuthnCredentialIdBase64) {
      return { ok: false, error: 'Device biometric credential not registered on this browser.' };
    }
    suppressBackgroundLockRef.current = true;
    const res = await verifyPlatformBiometricCredential(
      securitySettings.webAuthnCredentialIdBase64
    );
    suppressBackgroundLockRef.current = false;
    if (res.ok) {
      setIsAppUnlocked(true);
      return { ok: true };
    }
    return { ok: false, error: res.error };
  };

  const handleRecoverWithGoogleReauth = async (
    newPin: string,
    confirmPin: string
  ): Promise<{ ok: boolean; error?: string }> => {
    if (!auth.currentUser) {
      return { ok: false, error: 'No signed-in Google account found.' };
    }
    try {
      suppressBackgroundLockRef.current = true;
      await reauthenticateWithPopup(auth.currentUser, googleProvider);
      suppressBackgroundLockRef.current = false;
      const res = await resetForgottenPinAfterVerifiedIdentity(
        securitySettings,
        true,
        newPin,
        confirmPin
      );
      if (res.error || !res.settings) {
        return { ok: false, error: res.error || 'Unable to reset SEM-PIN.' };
      }
      persistSecurity(res.settings);
      setIsAppUnlocked(true);
      return { ok: true };
    } catch (err) {
      suppressBackgroundLockRef.current = false;
      return {
        ok: false,
        error: err instanceof Error ? err.message : 'Google account re-authentication failed.',
      };
    }
  };

  const handleRecoverWithDeviceAuth = async (
    newPin: string,
    confirmPin: string
  ): Promise<{ ok: boolean; error?: string }> => {
    if (!securitySettings.webAuthnCredentialIdBase64) {
      return { ok: false, error: 'Device biometrics not enrolled.' };
    }
    suppressBackgroundLockRef.current = true;
    const bioRes = await verifyPlatformBiometricCredential(
      securitySettings.webAuthnCredentialIdBase64
    );
    suppressBackgroundLockRef.current = false;
    if (!bioRes.ok) {
      return { ok: false, error: bioRes.error || 'Device authentication failed.' };
    }
    const res = await resetForgottenPinAfterVerifiedIdentity(
      securitySettings,
      true,
      newPin,
      confirmPin
    );
    if (res.error || !res.settings) {
      return { ok: false, error: res.error || 'Unable to reset SEM-PIN.' };
    }
    persistSecurity(res.settings);
    setIsAppUnlocked(true);
    return { ok: true };
  };

  const handleToggleAppLock = async (
    enabled: boolean
  ): Promise<{ ok: boolean; error?: string }> => {
    if (!enabled) {
      persistSecurity({
        ...securitySettings,
        appLockEnabled: false,
        method: 'NONE',
        fingerprintQuickUnlockEnabled: false,
      });
      setIsAppUnlocked(true);
      return { ok: true };
    }
    if (securitySettings.hasSemPinSet) {
      persistSecurity({
        ...securitySettings,
        appLockEnabled: true,
        method: 'SEM_PIN',
      });
      return { ok: true };
    }
    if (securitySettings.webAuthnCredentialIdBase64) {
      persistSecurity({
        ...securitySettings,
        appLockEnabled: true,
        method: 'DEVICE_LOCK',
      });
      return { ok: true };
    }
    return { ok: false, error: 'Create a 4–6 digit SEM-PIN first.' };
  };

  const handleSelectSecurityMethod = async (
    method: SecurityMethod
  ): Promise<{ ok: boolean; error?: string }> => {
    if (!activeUid) return { ok: false, error: 'No active session.' };
    if (method === 'NONE') {
      persistSecurity({
        ...securitySettings,
        appLockEnabled: false,
        method: 'NONE',
        fingerprintQuickUnlockEnabled: false,
      });
      setIsAppUnlocked(true);
      return { ok: true };
    }
    if (method === 'SEM_PIN') {
      if (!securitySettings.hasSemPinSet) {
        return { ok: false, error: 'Create a 4–6 digit SEM-PIN first.' };
      }
      persistSecurity({
        ...securitySettings,
        securityConfigured: true,
        appLockEnabled: true,
        method: 'SEM_PIN',
      });
      return { ok: true };
    }
    let credId = securitySettings.webAuthnCredentialIdBase64;
    if (!credId) {
      suppressBackgroundLockRef.current = true;
      const enroll = await registerPlatformBiometricCredential(
        activeUid,
        profile?.displayName || 'User'
      );
      suppressBackgroundLockRef.current = false;
      if (enroll.error || !enroll.credentialIdBase64) {
        return { ok: false, error: enroll.error || 'Device lock unavailable.' };
      }
      credId = enroll.credentialIdBase64;
    }
    persistSecurity({
      ...securitySettings,
      securityConfigured: true,
      appLockEnabled: true,
      method: 'DEVICE_LOCK',
      fingerprintQuickUnlockEnabled: true,
      webAuthnCredentialIdBase64: credId,
    });
    return { ok: true };
  };

  const handleCreateOrChangePin = async (
    currentPin: string,
    newPin: string,
    confirmPin: string
  ): Promise<{ ok: boolean; error?: string }> => {
    const res = securitySettings.hasSemPinSet
      ? await changeExistingSemPin(securitySettings, currentPin, newPin, confirmPin)
      : await createOrReplaceSemPinSettings(
          securitySettings,
          newPin,
          confirmPin,
          securitySettings.fingerprintQuickUnlockEnabled
        );
    if (res.error || !res.settings) {
      return { ok: false, error: res.error || 'Unable to save SEM-PIN.' };
    }
    persistSecurity(res.settings);
    return { ok: true };
  };

  const handleToggleFingerprintQuickUnlock = async (
    enabled: boolean
  ): Promise<{ ok: boolean; error?: string }> => {
    if (!activeUid) return { ok: false, error: 'No active session.' };
    let credId = securitySettings.webAuthnCredentialIdBase64;
    if (enabled && !credId) {
      suppressBackgroundLockRef.current = true;
      const enroll = await registerPlatformBiometricCredential(
        activeUid,
        profile?.displayName || 'User'
      );
      suppressBackgroundLockRef.current = false;
      if (enroll.error || !enroll.credentialIdBase64) {
        return { ok: false, error: enroll.error || 'Biometric enrollment failed.' };
      }
      credId = enroll.credentialIdBase64;
    }
    persistSecurity({
      ...securitySettings,
      fingerprintQuickUnlockEnabled: enabled,
      webAuthnCredentialIdBase64: credId,
    });
    return { ok: true };
  };

  const handleSelectTimeout = (seconds: AutoLockTimeoutSeconds) => {
    persistSecurity({ ...securitySettings, autoLockTimeoutSeconds: seconds });
  };

  const handleToggleLockOnClose = (enabled: boolean) => {
    persistSecurity({ ...securitySettings, lockOnAppClose: enabled });
  };

  const handleSaveTransaction = (data: {
    id?: string;
    type: TransactionType;
    amount: number;
    description: string;
    merchant: string;
    category: ExpenseCategory;
    customCategoryDetail?: string;
    paymentMethod: string;
    sourceOfMoney: string;
    note: string;
    transactionDate: string;
    transactionTime: string;
    referenceId: string;
    invoiceNumber: string;
    entrySource?: Transaction['entrySource'];
  }): string | null => {
    if (!activeUid || !profile) return 'No active user session.';
    if (!Number.isFinite(data.amount) || data.amount <= 0) {
      return 'Amount must be greater than zero.';
    }

    const dup = findDuplicateTransaction(
      {
        referenceId: data.referenceId,
        amount: data.amount,
        date: data.transactionDate,
        merchant: data.merchant,
        excludeId: data.id,
      },
      transactions
    );
    if (dup) return dup;

    const now = Date.now();
    const isNew = !data.id;
    const existing = data.id ? transactions.find((t) => t.id === data.id) : undefined;

    const balanceValidation = validateTransactionBalance(
      data.type,
      data.amount,
      summary.availableBalance,
      existing
    );
    if (!balanceValidation.allowed) {
      setInsufficientBalanceAlert(balanceValidation);
      return (
        balanceValidation.errorMessage ||
        "You don't have enough money to complete this expense."
      );
    }

    const resolvedCategory = data.type === 'INCOME' ? 'Income' : data.category;
    const resolvedCustomDetail =
      data.type === 'EXPENSE' && resolvedCategory === 'Other'
        ? (data.customCategoryDetail || '').trim().slice(0, 120)
        : '';

    const tx: Transaction = {
      id: data.id || `tx_${now}_${Math.random().toString(36).slice(2, 8)}`,
      uid: activeUid,
      type: data.type,
      amount: Math.round(data.amount * 100) / 100,
      description: data.description,
      merchant: data.merchant,
      category: resolvedCategory,
      customCategoryDetail: resolvedCustomDetail,
      paymentMethod: data.paymentMethod,
      sourceOfMoney: data.sourceOfMoney,
      note: data.note,
      transactionDate: data.transactionDate,
      transactionTime: data.transactionTime,
      timestampMillis: toTimestampMillis(data.transactionDate, data.transactionTime),
      referenceId: data.referenceId,
      invoiceNumber: data.invoiceNumber,
      entrySource: data.entrySource || existing?.entrySource || 'MANUAL',
      createdAtMillis: existing?.createdAtMillis || now,
      updatedAtMillis: now,
    };

    const nextTxs = isNew
      ? [...transactions, tx]
      : transactions.map((item) => (item.id === tx.id ? tx : item));

    setTransactions(nextTxs);
    saveLocalTransactions(activeUid, nextTxs);
    evaluateBudgetAlerts(nextTxs, profile);

    if (data.type === 'EXPENSE' && balanceValidation.willExhaustBalance) {
      setBalanceExhaustedAlert(true);
    }

    if (firebaseUid) {
      syncTransactionToFirestore(tx, isNew).catch((err) => {
        console.error('Firestore sync deferred:', err);
      });
    }
    return null;
  };

  const handleDeleteTransaction = (id: string) => {
    if (!activeUid) return;
    const nextTxs = transactions.filter((t) => t.id !== id);
    setTransactions(nextTxs);
    saveLocalTransactions(activeUid, nextTxs);

    if (firebaseUid) {
      deleteTransactionFromFirestore(firebaseUid, id).catch((err) => {
        console.error('Firestore delete deferred:', err);
      });
    }
  };

  const handleUpdateBudget = (newBudget: number) => {
    if (!profile) return;
    const updated: UserProfile = { ...profile, monthlyBudget: newBudget };
    setProfile(updated);
    saveLocalProfile(updated);
    if (firebaseUid) {
      syncProfileToFirestore(updated).catch(console.error);
    }
  };

  const handleUpdatePreferences = (prefs: NotificationPreferences) => {
    if (!profile) return;
    const updated: UserProfile = { ...profile, preferences: prefs };
    setProfile(updated);
    saveLocalProfile(updated);
    if (firebaseUid) {
      syncPreferencesToFirestore(firebaseUid, prefs).catch(console.error);
    }
  };

  const handleQuickCategorizePrompt = (
    prompt: PendingNotificationPrompt,
    category: ExpenseCategory
  ) => {
    if (!prompt.draftAmount || !prompt.draftMerchant) return;
    const now = new Date();
    const dateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const timeStr = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    handleSaveTransaction({
      type: 'EXPENSE',
      amount: prompt.draftAmount,
      description: prompt.draftMerchant,
      merchant: prompt.draftMerchant,
      category,
      customCategoryDetail: category === 'Other' ? prompt.draftMerchant : '',
      paymentMethod: prompt.draftPaymentMethod || 'UPI',
      sourceOfMoney: '',
      note: 'Confirmed from payment notification',
      transactionDate: dateStr,
      transactionTime: timeStr,
      referenceId: prompt.draftReferenceId || '',
      invoiceNumber: '',
      entrySource: 'UPI_CONFIRMED',
    });
    setNotificationQueue((prev) => prev.filter((item) => item.id !== prompt.id));
  };

  const handleBackupNow = async () => {
    if (!firebaseUid || !profile) return;
    setSyncStatusMessage('Backing up to Cloud Firestore...');
    try {
      await backupAllToFirestore(profile, transactions);
      const updated = { ...profile, lastBackupAt: new Date().toISOString() };
      setProfile(updated);
      saveLocalProfile(updated);
      setSyncStatusMessage('Cloud backup completed successfully.');
      if (profile.preferences.cloudBackupAlerts) {
        pushToast({
          id: `backup_${Date.now()}`,
          type: 'CLOUD_BACKUP',
          title: 'Cloud Backup Complete',
          body: `${transactions.length} transactions backed up to Firestore.`,
        });
      }
    } catch (err) {
      setSyncStatusMessage(err instanceof Error ? err.message : 'Backup failed');
    }
  };

  const handleRestoreCloud = async () => {
    if (!firebaseUid) return;
    setSyncStatusMessage('Restoring from Cloud Firestore...');
    try {
      const cloud = await restoreUserCloudData(firebaseUid);
      if (cloud.profile) {
        setProfile(cloud.profile);
        saveLocalProfile(cloud.profile);
      }
      setTransactions(cloud.transactions);
      saveLocalTransactions(firebaseUid, cloud.transactions);
      setSyncStatusMessage(`Restored ${cloud.transactions.length} transactions from Cloud Firestore.`);
    } catch (err) {
      setSyncStatusMessage(err instanceof Error ? err.message : 'Restore failed');
    }
  };

  const handleRequestPushPermission = async () => {
    if (typeof Notification === 'undefined') return;
    const perm = await Notification.requestPermission();
    setSyncStatusMessage(
      perm === 'granted'
        ? 'Browser notifications enabled.'
        : 'Browser notification permission declined.'
    );
  };

  const commitSplitState = useCallback(
    (updater: (prev: SplitWorkspaceLocalState) => SplitWorkspaceLocalState) => {
      setSplitState((prev) => {
        const next = updater(prev);
        if (activeUid) saveLocalSplitState(activeUid, next);
        return next;
      });
    },
    [activeUid]
  );

  const handleUpdateUpiProfile = async (upiId: string, verified: boolean) => {
    if (!profile || !activeUid) return;
    const appUserCode = profile.appUserCode || generateAppUserCode(activeUid);
    const updated: UserProfile = { ...profile, appUserCode, upiId, upiVerified: verified };
    setProfile(updated);
    saveLocalProfile(updated);
    commitSplitState((prev) => {
      const nextMembersByGroup: SplitWorkspaceLocalState['membersByGroup'] = {};
      for (const [gid, list] of Object.entries(prev.membersByGroup)) {
        nextMembersByGroup[gid] = list.map((m) =>
          m.uid === activeUid ? { ...m, upiId, upiVerified: verified } : m
        );
      }
      return { ...prev, membersByGroup: nextMembersByGroup };
    });
    if (firebaseUid) {
      await syncProfileToFirestore(updated);
      await syncUserDirectoryProfile({
        uid: firebaseUid,
        displayName: updated.displayName,
        appUserCode,
        upiId,
        upiVerified: verified,
      });
    }
  };

  const handleCreateGroup = async (params: {
    name: string;
    groupType: GroupType;
    iconKey: string;
    settlementDueDate: string;
  }): Promise<Group | null> => {
    if (!profile || !activeUid) return null;
    if (firebaseUid) {
      try {
        const { group, member } = await createGroupInFirestore({
          ...params,
          creatorUid: firebaseUid,
          creatorName: profile.displayName,
          creatorUpiId: profile.upiId || '',
          creatorUpiVerified: Boolean(profile.upiVerified),
        });
        commitSplitState((prev) => ({
          ...prev,
          groups: [group, ...prev.groups],
          membersByGroup: { ...prev.membersByGroup, [group.id]: [member] },
        }));
        return group;
      } catch (err) {
        console.error(err);
      }
    }
    const now = Date.now();
    const id = `grp_${now}_${Math.random().toString(36).slice(2, 6)}`;
    const group: Group = {
      id,
      name: params.name,
      groupType: params.groupType,
      iconKey: params.iconKey,
      joinCode: generateGroupJoinCode(id),
      creatorUid: activeUid,
      creatorName: profile.displayName,
      memberUids: [activeUid],
      settlementDueDate: params.settlementDueDate,
      createdAtMillis: now,
      updatedAtMillis: now,
    };
    const member = {
      id: `${id}_${activeUid}`,
      groupId: id,
      uid: activeUid,
      displayName: profile.displayName,
      appUserCode: profile.appUserCode || generateAppUserCode(activeUid),
      role: 'ADMIN' as const,
      status: 'ACTIVE' as const,
      upiId: profile.upiId || '',
      upiVerified: Boolean(profile.upiVerified),
      joinedAtMillis: now,
      updatedAtMillis: now,
    };
    commitSplitState((prev) => ({
      ...prev,
      groups: [group, ...prev.groups],
      membersByGroup: { ...prev.membersByGroup, [id]: [member] },
    }));
    return group;
  };

  const handleJoinGroupByCode = async (joinCode: string): Promise<string | null> => {
    if (!profile || !activeUid) return 'Sign in required.';
    if (!joinCode.trim()) return 'Enter a group join code.';
    if (firebaseUid) {
      try {
        await joinGroupByCodeInFirestore({
          joinCode,
          uid: firebaseUid,
          displayName: profile.displayName,
          upiId: profile.upiId || '',
          upiVerified: Boolean(profile.upiVerified),
        });
        const refreshed = await restoreSplitWorkspaceFromFirestore(
          firebaseUid,
          profile.email,
          splitState
        );
        setSplitState(refreshed);
        saveLocalSplitState(firebaseUid, refreshed);
        return null;
      } catch (err) {
        return err instanceof Error ? err.message : 'Unable to join group with that code.';
      }
    }
    return 'Connect with Firebase account to join remote groups by code.';
  };

  const handleLeaveGroup = async (group: Group): Promise<string | null> => {
    if (!activeUid) return 'No active user';
    const members = splitState.membersByGroup[group.id] || [];
    const expenses = splitState.expenses.filter((e) => e.groupId === group.id && !e.deleted);
    const settlements = splitState.settlements.filter((s) => s.groupId === group.id);
    const res = leaveGroupMemberState(group, members, activeUid, expenses, settlements);
    if (!res.allowed) return res.reason || 'Cannot leave group.';

    commitSplitState((prev) => ({
      ...prev,
      groups: prev.groups.filter((g) => g.id !== group.id),
      membersByGroup: {
        ...prev.membersByGroup,
        [group.id]: res.updatedMembers || members,
      },
    }));

    if (firebaseUid) {
      leaveGroupInFirestore(group, firebaseUid).catch(console.error);
    }
    return null;
  };

  const handleSendInvitation = async (inv: GroupInvitation): Promise<string | null> => {
    commitSplitState((prev) => ({
      ...prev,
      invitations: [inv, ...prev.invitations],
    }));
    if (firebaseUid) {
      try {
        await createInvitationInFirestore(inv);
      } catch (err) {
        return err instanceof Error ? err.message : 'Failed to send invitation.';
      }
    }
    return null;
  };

  const handleRespondInvitation = async (
    inv: GroupInvitation,
    decision: 'ACCEPT' | 'REJECT'
  ): Promise<string | null> => {
    if (!profile || !activeUid) return 'No active session';
    try {
      const result = respondToInvitationState(inv, activeUid, decision);
      if (firebaseUid) {
        await respondInvitationInFirestore({
          invitation: inv,
          decision,
          responderUid: firebaseUid,
          responderName: profile.displayName,
          responderUpiId: profile.upiId || '',
          responderUpiVerified: Boolean(profile.upiVerified),
        });
        const refreshed = await restoreSplitWorkspaceFromFirestore(
          firebaseUid,
          profile.email,
          splitState
        );
        setSplitState(refreshed);
        saveLocalSplitState(firebaseUid, refreshed);
        return null;
      }
      commitSplitState((prev) => ({
        ...prev,
        invitations: prev.invitations.map((item) =>
          item.id === inv.id ? result.invitation : item
        ),
        membersByGroup:
          decision === 'ACCEPT' && result.joinedMember
            ? {
                ...prev.membersByGroup,
                [inv.groupId]: [
                  ...(prev.membersByGroup[inv.groupId] || []),
                  {
                    ...result.joinedMember,
                    displayName: profile.displayName,
                    upiId: profile.upiId || '',
                    upiVerified: Boolean(profile.upiVerified),
                  },
                ],
              }
            : prev.membersByGroup,
      }));
      return null;
    } catch (err) {
      return err instanceof Error ? err.message : 'Unable to respond to invitation.';
    }
  };

  const handleSaveGroupExpense = async (
    expense: GroupExpense,
    isNew: boolean
  ): Promise<string | null> => {
    const queued: GroupExpense = {
      ...expense,
      pendingSync: Boolean(firebaseUid),
      syncAction: isNew ? 'CREATE' : 'UPDATE',
    };
    commitSplitState((prev) => ({
      ...prev,
      expenses: isNew
        ? [queued, ...prev.expenses]
        : prev.expenses.map((e) => (e.id === queued.id ? queued : e)),
    }));

    if (firebaseUid) {
      syncGroupExpenseToFirestore(queued, isNew)
        .then(() => {
          commitSplitState((prev) => ({
            ...prev,
            expenses: prev.expenses.map((e) =>
              e.id === queued.id ? { ...e, pendingSync: false } : e
            ),
          }));
        })
        .catch(() => {});
    }
    return null;
  };

  const handleDeleteGroupExpense = async (expense: GroupExpense) => {
    commitSplitState((prev) => ({
      ...prev,
      expenses: firebaseUid
        ? prev.expenses.map((e) =>
            e.id === expense.id
              ? { ...e, deleted: true, pendingSync: true, syncAction: 'DELETE' as const }
              : e
          )
        : prev.expenses.filter((e) => e.id !== expense.id),
    }));
    if (firebaseUid) {
      deleteGroupExpenseInFirestore(expense.id)
        .then(() => {
          commitSplitState((prev) => ({
            ...prev,
            expenses: prev.expenses.filter((e) => e.id !== expense.id),
          }));
        })
        .catch(() => {});
    }
  };

  const handleCreateSettlement = async (settlement: Settlement, request: PaymentRequest) => {
    commitSplitState((prev) => ({
      ...prev,
      settlements: [settlement, ...prev.settlements],
      paymentRequests: [request, ...prev.paymentRequests],
    }));
    if (firebaseUid) {
      createSettlementAndRequestInFirestore(settlement, request).catch(console.error);
    }
  };

  const handleUpdateSettlement = async (settlement: Settlement, paymentRequestId?: string) => {
    commitSplitState((prev) => ({
      ...prev,
      settlements: prev.settlements.map((s) => (s.id === settlement.id ? settlement : s)),
      paymentRequests: paymentRequestId
        ? prev.paymentRequests.map((r) =>
            r.id === paymentRequestId ? { ...r, status: settlement.status } : r
          )
        : prev.paymentRequests,
    }));
    if (firebaseUid) {
      updateSettlementStatusInFirestore(settlement, paymentRequestId).catch(console.error);
    }
  };

  const handleRecordPersonalSettlementExpense = (settlement: Settlement) => {
    if (!activeUid) return;
    const isReceiver = settlement.receiverUid === activeUid;
    const now = new Date();
    const dateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const timeStr = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    handleSaveTransaction({
      type: isReceiver ? 'INCOME' : 'EXPENSE',
      amount: settlement.amount,
      description: isReceiver
        ? `Group Settlement from ${settlement.payerName} (${settlement.groupName})`
        : `Group Settlement to ${settlement.receiverName} (${settlement.groupName})`,
      merchant: isReceiver ? settlement.payerName : settlement.receiverName,
      category: isReceiver ? 'Income' : 'Other',
      customCategoryDetail: isReceiver ? '' : `Group Settlement — ${settlement.groupName}`,
      paymentMethod: settlement.paymentApp || settlement.paymentMethod,
      sourceOfMoney: isReceiver ? 'Group Settlement' : '',
      note: settlement.paymentReference
        ? `Settlement Ref: ${settlement.paymentReference}`
        : settlement.note,
      transactionDate: dateStr,
      transactionTime: timeStr,
      referenceId: settlement.paymentReference || settlement.id,
      invoiceNumber: settlement.id,
      entrySource: 'UPI_CONFIRMED',
    });
  };

  const handleSignOut = async () => {
    if (firebaseUid) {
      await signOut(auth);
    }
    localStorage.removeItem('sem_use_offline_vault');
    setUseOfflineVault(false);
    setProfile(null);
    setTransactions([]);
    setSecuritySettings(DEFAULT_SECURITY_SETTINGS);
    setIsAppUnlocked(false);
    setRoute('dashboard');
  };

  if (!authReady) {
    return (
      <div className="min-h-screen bg-[#0B0D0F] flex items-center justify-center text-sm text-[#9CA3AF]">
        Initializing Smart Expense Manager...
      </div>
    );
  }

  if (!activeUid || !profile?.setupCompleted || !securitySettings.securityConfigured) {
    return (
      <OnboardingView
        isAuthenticated={Boolean(activeUid)}
        budgetCompleted={Boolean(profile?.setupCompleted)}
        userDisplayName={profile?.displayName || firebaseUserMeta.name}
        userEmail={profile?.email || firebaseUserMeta.email}
        authLoading={authLoading}
        authError={authError}
        onGoogleSignIn={handleGoogleSignIn}
        onUseLocalVault={handleUseLocalVault}
        onCompleteSetup={handleCompleteSetup}
        onCompleteSecuritySetup={handleCompleteSecuritySetup}
      />
    );
  }

  // Root Lock Gate: NEVER mount or render financial dashboard behind the lock screen
  if (securitySettings.appLockEnabled && !isAppUnlocked) {
    return (
      <AppLockScreen
        userEmail={profile.email}
        isFirebaseSignedIn={Boolean(firebaseUid)}
        settings={securitySettings}
        onVerifyPin={handleVerifyPin}
        onTriggerDeviceUnlock={handleTriggerDeviceUnlock}
        onRecoverWithGoogleReauth={handleRecoverWithGoogleReauth}
        onRecoverWithDeviceAuth={handleRecoverWithDeviceAuth}
      />
    );
  }

  const navItems: Array<{ id: AppRoute; label: string; icon: typeof Home }> = [
    { id: 'dashboard', label: 'Dashboard', icon: Home },
    { id: 'ledger', label: 'Ledger', icon: BookOpen },
    { id: 'splits', label: 'Split Expenses', icon: Users },
    { id: 'receipt', label: 'Receipt', icon: ScanLine },
    { id: 'analytics', label: 'Analytics', icon: BarChart3 },
    { id: 'settings', label: 'Settings', icon: Settings },
  ];

  const mobileTabs: Array<{ id: AppRoute; label: string; icon: typeof Home }> = [
    { id: 'dashboard', label: 'Home', icon: Home },
    { id: 'ledger', label: 'Ledger', icon: BookOpen },
    { id: 'splits', label: 'Split Expenses', icon: Users },
    { id: 'receipt', label: 'Receipt', icon: ScanLine },
    { id: 'analytics', label: 'Reports', icon: BarChart3 },
  ];

  return (
    <div className="min-h-screen bg-[#0B0D0F] text-[#F3F5F7] flex flex-col">
      {/* Top Bar Contract: Zone 1 Brand — Zone 2 Concise Nav — Zone 3 Primary CTA */}
      <header className="sticky top-0 z-30 bg-[#0B0D0F]/95 backdrop-blur-md border-b border-white/10">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-8">
          <button
            type="button"
            onClick={() => setRoute('dashboard')}
            className="text-lg font-bold tracking-tight text-[#F3F5F7] whitespace-nowrap shrink-0 cursor-pointer"
          >
            Smart Expense Manager
          </button>

          <nav className="hidden lg:flex items-center gap-6">
            {navItems.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setRoute(item.id)}
                className={`text-sm font-medium whitespace-nowrap shrink-0 transition-colors cursor-pointer py-1 border-b-2 ${
                  route === item.id || (route === 'security' && item.id === 'settings')
                    ? 'text-[#C6FF33] border-[#C6FF33]'
                    : 'text-[#9CA3AF] border-transparent hover:text-[#F3F5F7]'
                }`}
              >
                {item.label}
              </button>
            ))}
          </nav>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => setRoute('settings')}
              aria-label="Open Settings"
              className="lg:hidden min-h-[40px] min-w-[40px] rounded-xl bg-[#121519] border border-white/10 text-[#9CA3AF] hover:text-white inline-flex items-center justify-center cursor-pointer"
            >
              <Settings className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => setModalState({ open: true, type: 'EXPENSE' })}
              className="min-h-[40px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] whitespace-nowrap shrink-0 inline-flex items-center gap-1.5 cursor-pointer"
            >
              <Plus className="w-4 h-4" />
              <span>Add Expense</span>
            </button>
          </div>
        </div>
      </header>

      {/* Interactive Notification Prompts ("Where did you spend this money?" / Budget Alerts) */}
      {notificationQueue.length > 0 && canDispatchNotifications && (
        <div className="max-w-7xl w-full mx-auto px-4 sm:px-6 pt-4 space-y-2.5">
          {notificationQueue.map((prompt) => (
            <div
              key={prompt.id}
              className="bg-[#121519] border border-[#C6FF33]/40 rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
            >
              <div className="flex items-start gap-3">
                <Bell className="w-4 h-4 text-[#C6FF33] shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-semibold text-[#F3F5F7]">{prompt.title}</p>
                  <p className="text-xs text-[#9CA3AF] mt-0.5">{prompt.body}</p>
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-1.5">
                {prompt.type === 'TRANSACTION_CONFIRMATION' &&
                  QUICK_CONFIRM_CATEGORIES.map((cat) => (
                    <button
                      key={cat}
                      type="button"
                      onClick={() => handleQuickCategorizePrompt(prompt, cat)}
                      className="min-h-[34px] px-3 py-1 rounded-lg bg-[#181C22] border border-white/10 text-xs font-medium text-[#C6FF33] hover:bg-[#C6FF33] hover:text-[#0B0D0F] transition-colors cursor-pointer"
                    >
                      {cat}
                    </button>
                  ))}
                <button
                  type="button"
                  onClick={() =>
                    setNotificationQueue((prev) =>
                      prev.filter((item) => item.id !== prompt.id)
                    )
                  }
                  aria-label="Dismiss notification"
                  className="min-h-[34px] min-w-[34px] rounded-lg flex items-center justify-center text-[#9CA3AF] hover:text-white cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Main Content Canvas */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 py-6 pb-24 lg:pb-10">
        {route === 'dashboard' && (
          <DashboardView
            profile={profile}
            summary={summary}
            onAddTransaction={(type, presetCategory) =>
              setModalState({ open: true, type, presetCategory, editing: null })
            }
            onEditTransaction={(tx) =>
              setModalState({ open: true, type: tx.type, editing: tx })
            }
            onNavigate={setRoute}
          />
        )}

        {route === 'ledger' && (
          <LedgerView
            currencySymbol={profile.currencySymbol}
            entries={summary.ledger}
            firebaseUid={firebaseUid}
            onEditTransaction={(tx) =>
              setModalState({ open: true, type: tx.type, editing: tx })
            }
            onDeleteTransaction={handleDeleteTransaction}
            onAddTransaction={(type) =>
              setModalState({ open: true, type, editing: null })
            }
            onNavigate={setRoute}
          />
        )}

        {route === 'splits' && (
          <SplitExpensesView
            profile={profile}
            firebaseUid={firebaseUid}
            splitState={splitState}
            onUpdateUpiProfile={handleUpdateUpiProfile}
            onCreateGroup={handleCreateGroup}
            onJoinGroupByCode={handleJoinGroupByCode}
            onLeaveGroup={handleLeaveGroup}
            onSendInvitation={handleSendInvitation}
            onRespondInvitation={handleRespondInvitation}
            onSaveGroupExpense={handleSaveGroupExpense}
            onDeleteGroupExpense={handleDeleteGroupExpense}
            onCreateSettlement={handleCreateSettlement}
            onUpdateSettlement={handleUpdateSettlement}
            onRecordPersonalSettlementExpense={handleRecordPersonalSettlementExpense}
          />
        )}

        {route === 'receipt' && (
          <ReceiptImportView
            currencySymbol={profile.currencySymbol}
            existingTransactions={transactions}
            onConfirmSave={(data) => {
              const err = handleSaveTransaction({
                ...data,
                type: 'EXPENSE',
                sourceOfMoney: '',
                entrySource: 'RECEIPT_OCR',
              });
              if (!err) setRoute('ledger');
              return err;
            }}
          />
        )}

        {route === 'analytics' && (
          <AnalyticsView
            currencySymbol={profile.currencySymbol}
            summary={summary}
            transactions={transactions}
            onNavigate={setRoute}
          />
        )}

        {route === 'pdf' && <PdfExportView profile={profile} summary={summary} />}

        {route === 'settings' && (
          <SettingsView
            profile={profile}
            isFirebaseSignedIn={Boolean(firebaseUid)}
            securitySettings={securitySettings}
            syncStatusMessage={syncStatusMessage}
            onUpdateBudget={handleUpdateBudget}
            onUpdatePreferences={handleUpdatePreferences}
            onBackupNow={handleBackupNow}
            onRestoreCloud={handleRestoreCloud}
            onRequestBrowserPushPermission={handleRequestPushPermission}
            onNavigate={setRoute}
            onSignOut={handleSignOut}
          />
        )}

        {route === 'security' && (
          <SecuritySettingsView
            userEmail={profile.email}
            settings={securitySettings}
            onBack={() => setRoute('settings')}
            onToggleAppLock={handleToggleAppLock}
            onSelectMethod={handleSelectSecurityMethod}
            onCreateOrChangePin={handleCreateOrChangePin}
            onToggleFingerprintQuickUnlock={handleToggleFingerprintQuickUnlock}
            onSelectTimeout={handleSelectTimeout}
            onToggleLockOnClose={handleToggleLockOnClose}
            onLockNow={() => setIsAppUnlocked(false)}
          />
        )}
      </main>

      {/* Mobile Bottom Navigation Bar */}
      <nav className="lg:hidden fixed bottom-0 left-0 right-0 z-40 bg-[#121519]/95 backdrop-blur-md border-t border-white/10 grid grid-cols-5 h-16">
        {mobileTabs.map((tab) => {
          const Icon = tab.icon;
          const active = route === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setRoute(tab.id)}
              className={`flex flex-col items-center justify-center gap-1 min-h-[44px] px-1 cursor-pointer ${
                active ? 'text-[#C6FF33]' : 'text-[#9CA3AF]'
              }`}
            >
              <Icon className="w-4 h-4" />
              <span className="text-[10px] font-medium leading-none whitespace-nowrap">
                {tab.label}
              </span>
            </button>
          );
        })}
      </nav>

      {/* Add / Edit Transaction Modal */}
      {modalState.open && (
        <TransactionModal
          initialType={modalState.type}
          presetCategory={modalState.presetCategory}
          existing={modalState.editing}
          currencySymbol={profile.currencySymbol}
          onClose={() => setModalState({ open: false, type: 'EXPENSE', editing: null })}
          onSave={handleSaveTransaction}
          onDelete={handleDeleteTransaction}
        />
      )}

      {/* Insufficient Balance Warning Modal */}
      {insufficientBalanceAlert && (
        <InsufficientBalanceModal
          currencySymbol={profile.currencySymbol}
          validation={insufficientBalanceAlert}
          onAddBalance={() => {
            setInsufficientBalanceAlert(null);
            setModalState({ open: true, type: 'INCOME', editing: null });
          }}
          onDismiss={() => setInsufficientBalanceAlert(null)}
        />
      )}

      {/* Balance Exhausted Notice Modal */}
      {balanceExhaustedAlert && (
        <BalanceExhaustedModal onDismiss={() => setBalanceExhaustedAlert(false)} />
      )}
    </div>
  );
}
