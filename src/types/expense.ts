export type TransactionType = 'EXPENSE' | 'INCOME';

export type ExpenseCategory =
  | 'Food'
  | 'Travel'
  | 'Groceries'
  | 'Shopping'
  | 'Education'
  | 'Bills'
  | 'Entertainment'
  | 'Health'
  | 'Other'
  | 'Income';

export const EXPENSE_CATEGORIES: ExpenseCategory[] = [
  'Food',
  'Travel',
  'Groceries',
  'Shopping',
  'Education',
  'Bills',
  'Entertainment',
  'Health',
  'Other',
];

export const QUICK_CONFIRM_CATEGORIES: ExpenseCategory[] = [
  'Food',
  'Travel',
  'Shopping',
  'Groceries',
  'Bills',
  'Other',
];

export type EntrySource = 'MANUAL' | 'RECEIPT_OCR' | 'UPI_CONFIRMED' | 'CLOUD_RESTORE';

export interface Transaction {
  id: string;
  uid: string;
  type: TransactionType;
  amount: number;
  description: string;
  merchant: string;
  category: ExpenseCategory;
  customCategoryDetail?: string;
  paymentMethod: string;
  sourceOfMoney: string;
  note: string;
  transactionDate: string; // YYYY-MM-DD
  transactionTime: string; // HH:mm
  timestampMillis: number;
  referenceId: string;
  invoiceNumber: string;
  entrySource: EntrySource;
  createdAtMillis: number;
  updatedAtMillis: number;
  pendingSync?: boolean;
}

export interface LedgerEntry {
  transaction: Transaction;
  runningBalanceAfter: number;
}

export type BudgetStatusColor = 'GREEN' | 'YELLOW' | 'RED';

export interface BalanceValidationResult {
  allowed: boolean;
  currentAvailableBalance: number;
  shortfallAmount: number;
  willExhaustBalance: boolean;
  errorTitle?: string;
  errorMessage?: string;
}

export interface BalanceSummary {
  openingBalance: number;
  totalIncome: number;
  totalExpenses: number;
  availableBalance: number;
  monthlyBudget: number;
  monthlySpent: number;
  remainingBudget: number;
  budgetUsagePercent: number;
  budgetStatusColor: BudgetStatusColor;
  isBudgetExceeded: boolean;
  isBalanceExhausted: boolean;
  ledger: LedgerEntry[];
}

export interface NotificationPreferences {
  expenseReminders: boolean;
  reminderHour: number;
  reminderMinute: number;
  transactionConfirmations: boolean;
  budgetAlerts: boolean;
  dailySummary: boolean;
  weeklyReport: boolean;
  cloudBackupAlerts: boolean;
  quietHoursEnabled: boolean;
  quietStartHour: number;
  quietEndHour: number;
}

export interface UserProfile {
  uid: string;
  displayName: string;
  email: string;
  appUserCode?: string;
  upiId?: string;
  upiVerified?: boolean;
  openingBalance: number;
  monthlyBudget: number;
  currencySymbol: string;
  setupCompleted: boolean;
  lastBackupAt?: string | null;
  preferences: NotificationPreferences;
}

export interface UserDirectoryProfile {
  uid: string;
  appUserCode: string;
  displayName: string;
  upiId: string;
  upiVerified: boolean;
  updatedAtMillis: number;
}

export type SecurityMethod = 'SEM_PIN' | 'DEVICE_LOCK' | 'NONE';

export type AutoLockTimeoutSeconds = 0 | 30 | 60 | 300;

export interface SecuritySettings {
  securityConfigured: boolean;
  appLockEnabled: boolean;
  method: SecurityMethod;
  fingerprintQuickUnlockEnabled: boolean;
  autoLockTimeoutSeconds: AutoLockTimeoutSeconds;
  lockOnAppClose: boolean;
  hasSemPinSet: boolean;
  pinSaltBase64?: string;
  pinHashBase64?: string;
  webAuthnCredentialIdBase64?: string;
  failedAttempts: number;
  lockoutUntilMillis: number;
}

export interface DeviceSecurityCapability {
  platformAuthenticatorAvailable: boolean;
  reason: string;
}

export interface ExtractedReceiptDraft {
  merchantName: string;
  amount: string;
  transactionDate: string;
  transactionTime: string;
  paymentMethod: string;
  referenceId: string;
  invoiceNumber: string;
  suggestedCategory: ExpenseCategory;
  customCategoryDetail: string;
  rawText: string;
  missingFields: string[];
  duplicateReason: string | null;
}

export interface PendingNotificationPrompt {
  id: string;
  title: string;
  body: string;
  type:
    | 'TRANSACTION_CONFIRMATION'
    | 'BUDGET_ALERT'
    | 'EXPENSE_REMINDER'
    | 'DAILY_SUMMARY'
    | 'WEEKLY_REPORT'
    | 'CLOUD_BACKUP'
    | 'GROUP_INVITATION'
    | 'SETTLEMENT_REMINDER';
  draftAmount?: number;
  draftMerchant?: string;
  draftPaymentMethod?: string;
  draftReferenceId?: string;
}

export type AppRoute =
  | 'dashboard'
  | 'ledger'
  | 'splits'
  | 'receipt'
  | 'analytics'
  | 'pdf'
  | 'settings'
  | 'security';

export type DateFilterPreset =
  | 'ALL'
  | 'TODAY'
  | 'LAST_7_DAYS'
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'CUSTOM';

export interface TransactionFilter {
  searchQuery: string;
  datePreset: DateFilterPreset;
  startDate: string;
  endDate: string;
  categories: ExpenseCategory[];
  paymentMethods: string[];
  type: 'ALL' | TransactionType;
}

export type GroupType =
  | 'Roommates'
  | 'Friends'
  | 'Family'
  | 'Trip Group'
  | 'Custom Group';

export const GROUP_TYPES: GroupType[] = [
  'Roommates',
  'Friends',
  'Family',
  'Trip Group',
  'Custom Group',
];

export type GroupMemberRole = 'ADMIN' | 'MEMBER';
export type GroupMemberStatus = 'ACTIVE' | 'LEFT';

export interface Group {
  id: string;
  name: string;
  groupType: GroupType;
  iconKey: string;
  joinCode: string;
  creatorUid: string;
  creatorName: string;
  memberUids: string[];
  settlementDueDate: string; // YYYY-MM-DD or ''
  createdAtMillis: number;
  updatedAtMillis: number;
}

export interface GroupMember {
  id: string; // `${groupId}_${uid}`
  groupId: string;
  uid: string;
  displayName: string;
  appUserCode: string;
  role: GroupMemberRole;
  status: GroupMemberStatus;
  upiId: string;
  upiVerified: boolean;
  joinedAtMillis: number;
  updatedAtMillis: number;
}

export type InviteMethod = 'APP_ID' | 'EMAIL' | 'SMS' | 'WHATSAPP' | 'IN_APP';
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';

export interface GroupInvitation {
  id: string;
  groupId: string;
  groupName: string;
  groupType: GroupType;
  inviterUid: string;
  inviterName: string;
  inviteMethod: InviteMethod;
  targetUid: string;
  targetAppCode: string;
  targetEmail: string;
  targetPhoneMasked: string;
  inviteToken: string;
  status: InvitationStatus;
  createdAtMillis: number;
  updatedAtMillis: number;
}

export type SplitType = 'EQUAL' | 'CUSTOM_AMOUNT' | 'PERCENTAGE' | 'SHARES';

export interface GroupExpense {
  id: string;
  groupId: string;
  memberUids: string[];
  paidByUid: string;
  paidByName: string;
  amount: number;
  description: string;
  category: ExpenseCategory;
  customCategoryDetail?: string;
  transactionDate: string; // YYYY-MM-DD
  transactionTime: string; // HH:mm
  timestampMillis: number;
  splitType: SplitType;
  participantUids: string[];
  shares: Record<string, number>;
  shareInputs?: Record<string, number>;
  receiptUrl?: string;
  receiptReference?: string;
  note?: string;
  deleted?: boolean;
  pendingSync?: boolean;
  syncAction?: 'CREATE' | 'UPDATE' | 'DELETE';
  createdAtMillis: number;
  updatedAtMillis: number;
}

export type SettlementStatus =
  | 'PENDING'
  | 'PAYMENT_INITIATED'
  | 'AWAITING_CONFIRMATION'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED';

export type SettlementPaymentMethod = 'UPI' | 'QR' | 'MANUAL';

export type SettlementDueCategory = 'UPCOMING' | 'DUE' | 'OVERDUE' | 'COMPLETED';

export interface Settlement {
  id: string;
  groupId: string;
  groupName: string;
  memberUids: string[];
  payerUid: string;
  payerName: string;
  receiverUid: string;
  receiverName: string;
  receiverUpiId: string;
  amount: number;
  dueDate: string; // YYYY-MM-DD or ''
  reminderEnabled: boolean;
  status: SettlementStatus;
  paymentMethod: SettlementPaymentMethod;
  paymentApp?: string;
  paymentReference: string;
  idempotencyKey: string;
  payerConfirmedAtMillis: number | null;
  receiverConfirmedAtMillis: number | null;
  note: string;
  pendingSync?: boolean;
  createdAtMillis: number;
  updatedAtMillis: number;
}

export interface PaymentRequest {
  id: string;
  groupId: string;
  settlementId: string;
  memberUids: string[];
  requesterUid: string;
  requesterName: string;
  requesterUpiId: string;
  payerUid: string;
  payerName: string;
  amount: number;
  description: string;
  upiUri: string;
  transactionRef: string;
  dueDate: string;
  status: SettlementStatus;
  createdAtMillis: number;
  updatedAtMillis: number;
}

export interface MemberBalance {
  uid: string;
  displayName: string;
  appUserCode: string;
  upiId: string;
  upiVerified: boolean;
  role: GroupMemberRole;
  status: GroupMemberStatus;
  totalPaid: number;
  totalFairShare: number;
  settledPaid: number;
  settledReceived: number;
  netBalance: number; // + receives money, - owes money
}

export interface SimplifiedDebt {
  fromUid: string;
  fromName: string;
  toUid: string;
  toName: string;
  toUpiId: string;
  toUpiVerified: boolean;
  amount: number;
}

export interface GroupBalanceSummary {
  totalGroupExpenses: number;
  memberBalances: MemberBalance[];
  simplifiedDebts: SimplifiedDebt[];
}
