import {
  BalanceSummary,
  BalanceValidationResult,
  BudgetStatusColor,
  DateFilterPreset,
  ExpenseCategory,
  LedgerEntry,
  Transaction,
  TransactionFilter,
  TransactionType,
} from '../types/expense';

export function getDateRangeForPreset(preset: DateFilterPreset): {
  startDate: string;
  endDate: string;
} {
  const now = new Date();
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const today = fmt(now);

  switch (preset) {
    case 'TODAY':
      return { startDate: today, endDate: today };
    case 'LAST_7_DAYS': {
      const d = new Date(now);
      d.setDate(now.getDate() - 6);
      return { startDate: fmt(d), endDate: today };
    }
    case 'THIS_MONTH':
      return { startDate: `${today.slice(0, 7)}-01`, endDate: today };
    case 'LAST_MONTH': {
      const start = new Date(now.getFullYear(), now.getMonth() - 1, 1);
      const end = new Date(now.getFullYear(), now.getMonth(), 0);
      return { startDate: fmt(start), endDate: fmt(end) };
    }
    default:
      return { startDate: '', endDate: '' };
  }
}

export function applyTransactionFilter(
  entries: LedgerEntry[],
  filter: TransactionFilter
): {
  filtered: LedgerEntry[];
  expenseTotal: number;
  incomeTotal: number;
  netTotal: number;
} {
  const q = filter.searchQuery.trim().toLowerCase();
  const catSet = new Set(filter.categories);
  const methodSet = new Set(filter.paymentMethods);

  const filtered = entries.filter(({ transaction: tx }) => {
    if (filter.type !== 'ALL' && tx.type !== filter.type) return false;
    if (filter.startDate && tx.transactionDate < filter.startDate) return false;
    if (filter.endDate && tx.transactionDate > filter.endDate) return false;
    if (catSet.size > 0 && !catSet.has(tx.category)) return false;
    if (methodSet.size > 0 && !methodSet.has(tx.paymentMethod)) return false;
    if (q) {
      const hay =
        `${tx.description} ${tx.merchant} ${tx.customCategoryDetail || ''} ${tx.note} ${tx.referenceId} ${tx.paymentMethod} ${tx.sourceOfMoney}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });

  let expenseTotal = 0;
  let incomeTotal = 0;
  for (const { transaction: tx } of filtered) {
    if (tx.type === 'EXPENSE') expenseTotal += tx.amount;
    else incomeTotal += tx.amount;
  }

  return {
    filtered,
    expenseTotal: roundTwo(expenseTotal),
    incomeTotal: roundTwo(incomeTotal),
    netTotal: roundTwo(incomeTotal - expenseTotal),
  };
}

export function toTimestampMillis(dateStr: string, timeStr: string): number {
  const safeDate = /^\d{4}-\d{2}-\d{2}$/.test(dateStr)
    ? dateStr
    : new Date().toISOString().slice(0, 10);
  const safeTime = /^\d{2}:\d{2}/.test(timeStr) ? timeStr.slice(0, 5) : '00:00';
  const parsed = new Date(`${safeDate}T${safeTime}:00`);
  return Number.isNaN(parsed.getTime()) ? Date.now() : parsed.getTime();
}

function roundTwo(value: number): number {
  return Math.round(value * 100) / 100;
}

function normalizeZero(value: number): number {
  return Math.abs(value) < 0.005 ? 0 : value;
}

function getLocalMonthKey(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Dynamic monthly budget progress colour rule shared identically across Dashboard & Analytics:
 * - Below 50% -> GREEN
 * - Exactly 50% -> YELLOW
 * - Above 50% -> RED
 * - Zero monthly budget -> GREEN safely without division errors.
 */
export function getBudgetColorState(
  monthlySpent: number,
  monthlyBudget: number
): BudgetStatusColor {
  if (!Number.isFinite(monthlyBudget) || monthlyBudget <= 0) {
    return 'GREEN';
  }
  const percent = roundTwo((monthlySpent / monthlyBudget) * 100);
  if (Math.abs(percent - 50) < 0.005) {
    return 'YELLOW';
  }
  if (percent < 50) {
    return 'GREEN';
  }
  return 'RED';
}

/**
 * Removes an existing transaction's balance impact before validating an edit.
 */
export function calculateEffectiveAvailableBalance(
  currentAvailableBalance: number,
  existingTransaction?: Transaction | null
): number {
  if (!existingTransaction) {
    return normalizeZero(roundTwo(currentAvailableBalance));
  }
  const adjusted =
    existingTransaction.type === 'EXPENSE'
      ? currentAvailableBalance + existingTransaction.amount
      : currentAvailableBalance - existingTransaction.amount;
  return normalizeZero(roundTwo(adjusted));
}

/**
 * Validates whether a new or edited transaction keeps available balance >= 0.
 * Strictly independent of Monthly Budget limits.
 */
export function validateTransactionBalance(
  type: TransactionType,
  newAmount: number,
  currentAvailableBalance: number,
  existingTransaction?: Transaction | null
): BalanceValidationResult {
  const effectiveAvailable = calculateEffectiveAvailableBalance(
    currentAvailableBalance,
    existingTransaction
  );

  if (type === 'INCOME') {
    const resultingBalance = normalizeZero(roundTwo(effectiveAvailable + newAmount));
    if (resultingBalance < -0.005) {
      return {
        allowed: false,
        currentAvailableBalance: effectiveAvailable,
        shortfallAmount: roundTwo(-resultingBalance),
        willExhaustBalance: false,
        errorTitle: 'Insufficient Balance!',
        errorMessage:
          'Reducing this income entry would push your available balance below zero.',
      };
    }
    return {
      allowed: true,
      currentAvailableBalance: effectiveAvailable,
      shortfallAmount: 0,
      willExhaustBalance: Math.abs(resultingBalance) < 0.005,
    };
  }

  const cleanAmount = roundTwo(newAmount);
  if (cleanAmount > effectiveAvailable + 0.001) {
    return {
      allowed: false,
      currentAvailableBalance: effectiveAvailable,
      shortfallAmount: roundTwo(Math.max(0, cleanAmount - effectiveAvailable)),
      willExhaustBalance: false,
      errorTitle: 'Insufficient Balance!',
      errorMessage: "You don't have enough money to complete this expense.",
    };
  }

  const remainingAfter = normalizeZero(roundTwo(effectiveAvailable - cleanAmount));
  return {
    allowed: true,
    currentAvailableBalance: effectiveAvailable,
    shortfallAmount: 0,
    willExhaustBalance: Math.abs(remainingAfter) < 0.005,
  };
}

/**
 * Deterministic chronological balance engine matching Android BalanceCalculator.kt:
 * - Always sorts transactions by actual transaction date & time ascending.
 * - Opening Balance + Total Income - Total Expenses = Available Balance.
 * - Monthly budget usage is tracked independently for the target YYYY-MM month.
 */
export function calculateBalanceSummary(
  openingBalance: number,
  monthlyBudget: number,
  transactions: Transaction[],
  targetMonth?: string
): BalanceSummary {
  const monthKey = targetMonth || getLocalMonthKey();

  const chronological = [...transactions].sort((a, b) => {
    if (a.timestampMillis !== b.timestampMillis) {
      return a.timestampMillis - b.timestampMillis;
    }
    if (a.createdAtMillis !== b.createdAtMillis) {
      return a.createdAtMillis - b.createdAtMillis;
    }
    return a.id.localeCompare(b.id);
  });

  let runningBalance = openingBalance;
  let totalIncome = 0;
  let totalExpenses = 0;
  let monthlySpent = 0;

  const ledger: LedgerEntry[] = chronological.map((tx) => {
    if (tx.type === 'INCOME') {
      totalIncome += tx.amount;
      runningBalance += tx.amount;
    } else {
      totalExpenses += tx.amount;
      runningBalance -= tx.amount;
      if (tx.transactionDate.startsWith(monthKey)) {
        monthlySpent += tx.amount;
      }
    }
    return {
      transaction: tx,
      runningBalanceAfter: normalizeZero(roundTwo(runningBalance)),
    };
  });

  const cleanBudget = normalizeZero(roundTwo(Math.max(0, monthlyBudget)));
  const cleanMonthlySpent = normalizeZero(roundTwo(monthlySpent));
  const availableBalance = normalizeZero(
    roundTwo(openingBalance + totalIncome - totalExpenses)
  );
  const remainingBudget = normalizeZero(roundTwo(cleanBudget - cleanMonthlySpent));
  const budgetUsagePercent =
    cleanBudget > 0 ? normalizeZero(roundTwo((cleanMonthlySpent / cleanBudget) * 100)) : 0;
  const budgetStatusColor = getBudgetColorState(cleanMonthlySpent, cleanBudget);
  const isBudgetExceeded = cleanBudget > 0 && budgetUsagePercent >= 100;
  const isBalanceExhausted = Math.abs(availableBalance) < 0.005;

  return {
    openingBalance: normalizeZero(roundTwo(openingBalance)),
    totalIncome: normalizeZero(roundTwo(totalIncome)),
    totalExpenses: normalizeZero(roundTwo(totalExpenses)),
    availableBalance,
    monthlyBudget: cleanBudget,
    monthlySpent: cleanMonthlySpent,
    remainingBudget,
    budgetUsagePercent,
    budgetStatusColor,
    isBudgetExceeded,
    isBalanceExhausted,
    ledger,
  };
}

export function findDuplicateTransaction(
  candidate: {
    referenceId?: string;
    amount?: number;
    date?: string;
    merchant?: string;
    excludeId?: string;
  },
  existing: Transaction[]
): string | null {
  const ref = candidate.referenceId?.trim();
  if (ref) {
    const match = existing.find(
      (tx) =>
        tx.id !== candidate.excludeId &&
        tx.referenceId.trim().toLowerCase() === ref.toLowerCase()
    );
    if (match) {
      return `Reference ID "${ref}" already exists (${match.description} on ${match.transactionDate}).`;
    }
  }

  if (
    typeof candidate.amount === 'number' &&
    candidate.amount > 0 &&
    candidate.date &&
    candidate.merchant?.trim()
  ) {
    const normMerchant = candidate.merchant.trim().toLowerCase();
    const match = existing.find(
      (tx) =>
        tx.id !== candidate.excludeId &&
        tx.transactionDate === candidate.date &&
        Math.abs(tx.amount - candidate.amount!) < 0.01 &&
        (tx.merchant.trim().toLowerCase() === normMerchant ||
          tx.description.trim().toLowerCase() === normMerchant)
    );
    if (match) {
      return `A matching transaction for "${candidate.merchant}" (${candidate.amount}) on ${candidate.date} already exists.`;
    }
  }

  return null;
}

export function formatMoney(amount: number, currencySymbol = '₹'): string {
  const absFormatted = Math.abs(amount).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return amount < 0 ? `-${currencySymbol}${absFormatted}` : `${currencySymbol}${absFormatted}`;
}

export function formatCategoryLabel(
  category: ExpenseCategory,
  customCategoryDetail?: string
): string {
  const detail = customCategoryDetail?.trim();
  if (category === 'Other' && detail) {
    return `Other — ${detail}`;
  }
  return category;
}

export function computeOtherCategoryBreakdown(
  transactions: Transaction[],
  monthFilter?: string
): Array<{ label: string; total: number; percent: number; count: number }> {
  const otherExpenses = transactions.filter(
    (t) =>
      t.type === 'EXPENSE' &&
      t.category === 'Other' &&
      (!monthFilter || t.transactionDate.startsWith(monthFilter))
  );
  const grandTotal = otherExpenses.reduce((sum, t) => sum + t.amount, 0);
  const map = new Map<string, { total: number; count: number }>();
  for (const tx of otherExpenses) {
    const label = tx.customCategoryDetail?.trim() || tx.description.trim() || 'Other';
    const prev = map.get(label) || { total: 0, count: 0 };
    map.set(label, { total: prev.total + tx.amount, count: prev.count + 1 });
  }
  return Array.from(map.entries())
    .map(([label, val]) => ({
      label,
      total: roundTwo(val.total),
      percent: grandTotal > 0 ? roundTwo((val.total / grandTotal) * 100) : 0,
      count: val.count,
    }))
    .sort((a, b) => b.total - a.total);
}

export function computeCategoryBreakdown(
  transactions: Transaction[],
  monthFilter?: string
): Array<{ category: ExpenseCategory; total: number; percent: number }> {
  const expenses = transactions.filter(
    (t) =>
      t.type === 'EXPENSE' &&
      (!monthFilter || t.transactionDate.startsWith(monthFilter))
  );
  const grandTotal = expenses.reduce((sum, t) => sum + t.amount, 0);
  const map = new Map<ExpenseCategory, number>();
  for (const tx of expenses) {
    map.set(tx.category, (map.get(tx.category) || 0) + tx.amount);
  }
  return Array.from(map.entries())
    .map(([category, total]) => ({
      category,
      total: roundTwo(total),
      percent: grandTotal > 0 ? roundTwo((total / grandTotal) * 100) : 0,
    }))
    .sort((a, b) => b.total - a.total);
}

export function computeMonthlySeries(transactions: Transaction[], monthsCount = 6) {
  const now = new Date();
  const series: Array<{ month: string; label: string; income: number; expense: number }> = [];
  for (let i = monthsCount - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const label = d.toLocaleDateString('en-US', { month: 'short' });
    let income = 0;
    let expense = 0;
    for (const tx of transactions) {
      if (tx.transactionDate.startsWith(key)) {
        if (tx.type === 'INCOME') income += tx.amount;
        else expense += tx.amount;
      }
    }
    series.push({ month: key, label, income: roundTwo(income), expense: roundTwo(expense) });
  }
  return series;
}

export function computeWeeklyDailySeries(transactions: Transaction[]) {
  const today = new Date();
  const days: Array<{ date: string; label: string; expense: number; income: number }> = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(today.getDate() - i);
    const dateKey = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    const label = d.toLocaleDateString('en-US', { weekday: 'short' });
    let expense = 0;
    let income = 0;
    for (const tx of transactions) {
      if (tx.transactionDate === dateKey) {
        if (tx.type === 'EXPENSE') expense += tx.amount;
        else income += tx.amount;
      }
    }
    days.push({ date: dateKey, label, expense: roundTwo(expense), income: roundTwo(income) });
  }
  return days;
}
