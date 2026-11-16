import React, { useMemo, useState } from 'react';
import {
  Search,
  FileDown,
  Plus,
  Trash2,
  SlidersHorizontal,
  X,
  RotateCcw,
  Calendar,
  Cloud,
} from 'lucide-react';
import {
  AppRoute,
  DateFilterPreset,
  EXPENSE_CATEGORIES,
  ExpenseCategory,
  LedgerEntry,
  Transaction,
  TransactionFilter,
  TransactionType,
} from '../types/expense';
import {
  applyTransactionFilter,
  formatCategoryLabel,
  formatMoney,
  getDateRangeForPreset,
} from '../lib/ledgerEngine';
import { queryFilteredTransactionsFromFirestore } from '../lib/storageSync';

interface LedgerViewProps {
  currencySymbol: string;
  entries: LedgerEntry[];
  firebaseUid?: string | null;
  onEditTransaction: (tx: Transaction) => void;
  onDeleteTransaction: (id: string) => void;
  onAddTransaction: (type: TransactionType) => void;
  onNavigate: (route: AppRoute) => void;
}

const DEFAULT_PAYMENT_METHODS = [
  'UPI',
  'Google Pay (UPI)',
  'PhonePe (UPI)',
  'Paytm (UPI)',
  'Debit Card',
  'Credit Card',
  'Cash',
  'Net Banking',
];

const DATE_PRESETS: Array<{ id: DateFilterPreset; label: string }> = [
  { id: 'ALL', label: 'All Time' },
  { id: 'TODAY', label: 'Today' },
  { id: 'LAST_7_DAYS', label: '7 Days' },
  { id: 'THIS_MONTH', label: 'This Month' },
  { id: 'LAST_MONTH', label: 'Last Month' },
  { id: 'CUSTOM', label: 'Custom' },
];

const INITIAL_FILTER: TransactionFilter = {
  searchQuery: '',
  datePreset: 'ALL',
  startDate: '',
  endDate: '',
  categories: [],
  paymentMethods: [],
  type: 'ALL',
};

export const LedgerView: React.FC<LedgerViewProps> = ({
  currencySymbol,
  entries,
  firebaseUid,
  onEditTransaction,
  onDeleteTransaction,
  onAddTransaction,
  onNavigate,
}) => {
  const [filter, setFilter] = useState<TransactionFilter>(INITIAL_FILTER);
  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Transaction | null>(null);
  const [cloudQueryStatus, setCloudQueryStatus] = useState<string | null>(null);
  const [cloudQueryLoading, setCloudQueryLoading] = useState(false);

  const today = new Date().toISOString().slice(0, 10);
  const weekAgo = new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10);
  const currentMonth = today.slice(0, 7);

  const availableCategories = useMemo<ExpenseCategory[]>(
    () => [...EXPENSE_CATEGORIES, 'Income'],
    []
  );

  const availablePaymentMethods = useMemo(() => {
    const set = new Set<string>(DEFAULT_PAYMENT_METHODS);
    for (const { transaction: tx } of entries) {
      const m = tx.paymentMethod.trim();
      if (m) set.add(m);
    }
    return Array.from(set);
  }, [entries]);

  const periodSummaries = useMemo(() => {
    let dailyExp = 0;
    let weeklyExp = 0;
    let monthlyExp = 0;
    let monthlyInc = 0;
    for (const { transaction: tx } of entries) {
      if (tx.type === 'EXPENSE') {
        if (tx.transactionDate === today) dailyExp += tx.amount;
        if (tx.transactionDate >= weekAgo && tx.transactionDate <= today) weeklyExp += tx.amount;
        if (tx.transactionDate.startsWith(currentMonth)) monthlyExp += tx.amount;
      } else if (tx.transactionDate.startsWith(currentMonth)) {
        monthlyInc += tx.amount;
      }
    }
    return { dailyExp, weeklyExp, monthlyExp, monthlyInc };
  }, [entries, today, weekAgo, currentMonth]);

  const { filtered, expenseTotal, incomeTotal, netTotal } = useMemo(
    () => applyTransactionFilter(entries, filter),
    [entries, filter]
  );

  const filteredNewestFirst = useMemo(() => [...filtered].reverse(), [filtered]);

  const groupedByDate = useMemo(() => {
    const map = new Map<string, LedgerEntry[]>();
    for (const entry of filteredNewestFirst) {
      const d = entry.transaction.transactionDate;
      const list = map.get(d) || [];
      list.push(entry);
      map.set(d, list);
    }
    return Array.from(map.entries());
  }, [filteredNewestFirst]);

  const structuredFilterCount = useMemo(() => {
    let count = 0;
    if (filter.type !== 'ALL') count += 1;
    if (filter.datePreset !== 'ALL' || filter.startDate || filter.endDate) count += 1;
    count += filter.categories.length;
    count += filter.paymentMethods.length;
    return count;
  }, [filter]);

  const hasAnyActiveFilter =
    structuredFilterCount > 0 || filter.searchQuery.trim().length > 0;

  const handleSelectDatePreset = (preset: DateFilterPreset) => {
    setCloudQueryStatus(null);
    if (preset === 'CUSTOM') {
      setFilter((prev) => ({ ...prev, datePreset: 'CUSTOM' }));
      return;
    }
    const range = getDateRangeForPreset(preset);
    setFilter((prev) => ({
      ...prev,
      datePreset: preset,
      startDate: range.startDate,
      endDate: range.endDate,
    }));
  };

  const handleStartDateChange = (nextStart: string) => {
    setCloudQueryStatus(null);
    setFilter((prev) => ({
      ...prev,
      datePreset: nextStart || prev.endDate ? 'CUSTOM' : 'ALL',
      startDate: nextStart,
      endDate: prev.endDate && nextStart && nextStart > prev.endDate ? nextStart : prev.endDate,
    }));
  };

  const handleEndDateChange = (nextEnd: string) => {
    setCloudQueryStatus(null);
    setFilter((prev) => ({
      ...prev,
      datePreset: prev.startDate || nextEnd ? 'CUSTOM' : 'ALL',
      startDate: prev.startDate && nextEnd && nextEnd < prev.startDate ? nextEnd : prev.startDate,
      endDate: nextEnd,
    }));
  };

  const toggleCategory = (cat: ExpenseCategory) => {
    setCloudQueryStatus(null);
    setFilter((prev) => {
      const exists = prev.categories.includes(cat);
      return {
        ...prev,
        categories: exists
          ? prev.categories.filter((c) => c !== cat)
          : [...prev.categories, cat],
      };
    });
  };

  const togglePaymentMethod = (method: string) => {
    setCloudQueryStatus(null);
    setFilter((prev) => {
      const exists = prev.paymentMethods.includes(method);
      return {
        ...prev,
        paymentMethods: exists
          ? prev.paymentMethods.filter((m) => m !== method)
          : [...prev.paymentMethods, method],
      };
    });
  };

  const handleClearAll = () => {
    setCloudQueryStatus(null);
    setFilter(INITIAL_FILTER);
  };

  const handleRunCloudQuery = async () => {
    if (!firebaseUid) return;
    setCloudQueryLoading(true);
    setCloudQueryStatus(null);
    try {
      const remoteMatches = await queryFilteredTransactionsFromFirestore(firebaseUid, filter);
      setCloudQueryStatus(
        `Firestore composite query matched ${remoteMatches.length} cloud ${
          remoteMatches.length === 1 ? 'record' : 'records'
        }.`
      );
    } catch (err) {
      setCloudQueryStatus(
        err instanceof Error ? err.message : 'Cloud index query failed.'
      );
    } finally {
      setCloudQueryLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
            Transaction Ledger
          </h1>
          <p className="text-sm text-[#9CA3AF] mt-1">
            Combine date ranges, categories, payment methods, and descriptions with intact chronological running balances.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            type="button"
            onClick={() => onNavigate('pdf')}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:bg-[#20262e] inline-flex items-center gap-2 whitespace-nowrap cursor-pointer"
          >
            <FileDown className="w-4 h-4 text-[#C6FF33]" />
            <span>Export PDF</span>
          </button>
          <button
            type="button"
            onClick={() => onAddTransaction('EXPENSE')}
            className="min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-xs hover:bg-[#d4ff66] inline-flex items-center gap-1.5 whitespace-nowrap cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>New Entry</span>
          </button>
        </div>
      </div>

      {/* Daily / Weekly / Monthly Summary Strip */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-4">
          <p className="text-xs text-[#9CA3AF]">Today&apos;s Spend</p>
          <p className="mt-1 text-xl font-bold font-mono-num text-[#FF6B2C]">
            {formatMoney(periodSummaries.dailyExp, currencySymbol)}
          </p>
        </div>
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-4">
          <p className="text-xs text-[#9CA3AF]">7-Day Spend</p>
          <p className="mt-1 text-xl font-bold font-mono-num text-[#FF6B2C]">
            {formatMoney(periodSummaries.weeklyExp, currencySymbol)}
          </p>
        </div>
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-4">
          <p className="text-xs text-[#9CA3AF]">Monthly Spend</p>
          <p className="mt-1 text-xl font-bold font-mono-num text-[#FF6B2C]">
            {formatMoney(periodSummaries.monthlyExp, currencySymbol)}
          </p>
        </div>
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-4">
          <p className="text-xs text-[#9CA3AF]">Monthly Income Added</p>
          <p className="mt-1 text-xl font-bold font-mono-num text-[#2CE59B]">
            {formatMoney(periodSummaries.monthlyInc, currencySymbol)}
          </p>
        </div>
      </div>

      {/* Search & Filter Command Bar */}
      <div className="bg-[#121519] border border-white/10 rounded-2xl p-4 space-y-4">
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
          <div className="flex-1 relative">
            <Search className="w-4 h-4 text-[#9CA3AF] absolute left-3.5 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={filter.searchQuery}
              onChange={(e) => {
                setCloudQueryStatus(null);
                setFilter((prev) => ({ ...prev, searchQuery: e.target.value }));
              }}
              placeholder="Search description, merchant, note, payment method, ref ID..."
              aria-label="Search transactions"
              className="w-full min-h-[44px] pl-10 pr-10 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
            {filter.searchQuery && (
              <button
                type="button"
                onClick={() => setFilter((prev) => ({ ...prev, searchQuery: '' }))}
                aria-label="Clear search query"
                className="min-h-[36px] min-w-[36px] rounded-lg flex items-center justify-center text-[#9CA3AF] hover:text-white absolute right-1.5 top-1/2 -translate-y-1/2 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            )}
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => setFiltersExpanded((prev) => !prev)}
              aria-expanded={filtersExpanded}
              className={`min-h-[44px] px-4 py-2 rounded-xl border text-xs font-semibold inline-flex items-center gap-2 transition-colors cursor-pointer ${
                filtersExpanded || structuredFilterCount > 0
                  ? 'bg-[#C6FF33]/15 border-[#C6FF33]/50 text-[#C6FF33]'
                  : 'bg-[#181C22] border-white/10 text-[#F3F5F7] hover:bg-[#20262e]'
              }`}
            >
              <SlidersHorizontal className="w-4 h-4" />
              <span>
                Filters{structuredFilterCount > 0 ? ` (${structuredFilterCount})` : ''}
              </span>
            </button>

            {firebaseUid && (
              <button
                type="button"
                onClick={handleRunCloudQuery}
                disabled={cloudQueryLoading}
                className="min-h-[44px] px-3.5 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:bg-[#20262e] disabled:opacity-60 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <Cloud className="w-4 h-4 text-[#C6FF33]" />
                <span>{cloudQueryLoading ? 'Querying...' : 'Verify Cloud'}</span>
              </button>
            )}

            {hasAnyActiveFilter && (
              <button
                type="button"
                onClick={handleClearAll}
                className="min-h-[44px] px-3.5 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#FF6B2C] hover:bg-[#FF6B2C]/10 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                <span>Clear All</span>
              </button>
            )}
          </div>
        </div>

        {/* Expandable Multi-Criteria Filter Panel */}
        {filtersExpanded && (
          <div className="pt-4 border-t border-white/10 space-y-5">
            {/* Transaction Type & Date Range */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
              <div className="lg:col-span-4 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-[#F3F5F7]">
                    Transaction Type
                  </span>
                  {filter.type !== 'ALL' && (
                    <button
                      type="button"
                      onClick={() => setFilter((prev) => ({ ...prev, type: 'ALL' }))}
                      className="text-xs text-[#C6FF33] hover:underline cursor-pointer"
                    >
                      Reset
                    </button>
                  )}
                </div>
                <div className="grid grid-cols-3 gap-1.5 p-1 bg-[#0B0D0F] rounded-xl border border-white/10">
                  {(['ALL', 'EXPENSE', 'INCOME'] as const).map((t) => {
                    const active = filter.type === t;
                    const label =
                      t === 'ALL' ? 'All' : t === 'EXPENSE' ? 'Expenses' : 'Income';
                    return (
                      <button
                        key={t}
                        type="button"
                        onClick={() => {
                          setCloudQueryStatus(null);
                          setFilter((prev) => ({ ...prev, type: t }));
                        }}
                        className={`min-h-[38px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                          active
                            ? t === 'EXPENSE'
                              ? 'bg-[#FF6B2C] text-white'
                              : 'bg-[#C6FF33] text-[#0B0D0F]'
                            : 'text-[#9CA3AF] hover:text-white'
                        }`}
                      >
                        {label}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="lg:col-span-8 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-[#F3F5F7] inline-flex items-center gap-1.5">
                    <Calendar className="w-3.5 h-3.5 text-[#C6FF33]" />
                    <span>Date Range</span>
                  </span>
                  {(filter.datePreset !== 'ALL' || filter.startDate || filter.endDate) && (
                    <button
                      type="button"
                      onClick={() =>
                        setFilter((prev) => ({
                          ...prev,
                          datePreset: 'ALL',
                          startDate: '',
                          endDate: '',
                        }))
                      }
                      className="text-xs text-[#C6FF33] hover:underline cursor-pointer"
                    >
                      Reset
                    </button>
                  )}
                </div>

                <div className="flex flex-wrap gap-2">
                  {DATE_PRESETS.map((preset) => {
                    const active = filter.datePreset === preset.id;
                    return (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleSelectDatePreset(preset.id)}
                        className={`min-h-[38px] px-3 py-1.5 rounded-xl text-xs font-medium border transition-colors cursor-pointer ${
                          active
                            ? 'bg-[#C6FF33] border-[#C6FF33] text-[#0B0D0F] font-semibold'
                            : 'bg-[#0B0D0F] border-white/10 text-[#9CA3AF] hover:text-white'
                        }`}
                      >
                        {preset.label}
                      </button>
                    );
                  })}
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
                  <div>
                    <label className="block text-[11px] text-[#9CA3AF] mb-1">
                      From Date
                    </label>
                    <input
                      type="date"
                      value={filter.startDate}
                      max={filter.endDate || undefined}
                      onChange={(e) => handleStartDateChange(e.target.value)}
                      aria-label="Filter start date"
                      className="w-full min-h-[42px] px-3 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-xs font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] text-[#9CA3AF] mb-1">
                      To Date
                    </label>
                    <input
                      type="date"
                      value={filter.endDate}
                      min={filter.startDate || undefined}
                      onChange={(e) => handleEndDateChange(e.target.value)}
                      aria-label="Filter end date"
                      className="w-full min-h-[42px] px-3 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-xs font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Multi-select Categories */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-[#F3F5F7]">
                  Categories {filter.categories.length > 0 ? `(${filter.categories.length} selected)` : '(All)'}
                </span>
                {filter.categories.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setFilter((prev) => ({ ...prev, categories: [] }))}
                    className="text-xs text-[#C6FF33] hover:underline cursor-pointer"
                  >
                    Reset Categories
                  </button>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                {availableCategories.map((cat) => {
                  const selected = filter.categories.includes(cat);
                  return (
                    <button
                      key={cat}
                      type="button"
                      onClick={() => toggleCategory(cat)}
                      aria-pressed={selected}
                      className={`min-h-[38px] px-3.5 py-1.5 rounded-xl text-xs font-medium border transition-colors cursor-pointer ${
                        selected
                          ? 'bg-[#C6FF33] border-[#C6FF33] text-[#0B0D0F] font-semibold'
                          : 'bg-[#0B0D0F] border-white/10 text-[#9CA3AF] hover:text-white'
                      }`}
                    >
                      {cat}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Multi-select Payment Methods */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-[#F3F5F7]">
                  Payment Methods {filter.paymentMethods.length > 0 ? `(${filter.paymentMethods.length} selected)` : '(All)'}
                </span>
                {filter.paymentMethods.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setFilter((prev) => ({ ...prev, paymentMethods: [] }))}
                    className="text-xs text-[#C6FF33] hover:underline cursor-pointer"
                  >
                    Reset Methods
                  </button>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                {availablePaymentMethods.map((method) => {
                  const selected = filter.paymentMethods.includes(method);
                  return (
                    <button
                      key={method}
                      type="button"
                      onClick={() => togglePaymentMethod(method)}
                      aria-pressed={selected}
                      className={`min-h-[38px] px-3.5 py-1.5 rounded-xl text-xs font-medium border transition-colors cursor-pointer ${
                        selected
                          ? 'bg-[#C6FF33] border-[#C6FF33] text-[#0B0D0F] font-semibold'
                          : 'bg-[#0B0D0F] border-white/10 text-[#9CA3AF] hover:text-white'
                      }`}
                    >
                      {method}
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* Removable Active Filter Controls Strip */}
        {hasAnyActiveFilter && (
          <div className="pt-3 border-t border-white/10 flex flex-wrap items-center gap-2">
            {filter.searchQuery.trim() && (
              <button
                type="button"
                onClick={() => setFilter((prev) => ({ ...prev, searchQuery: '' }))}
                className="min-h-[34px] px-3 py-1 rounded-lg bg-[#181C22] border border-white/10 text-xs text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <span>Search: &ldquo;{filter.searchQuery.trim()}&rdquo;</span>
                <X className="w-3.5 h-3.5 text-[#9CA3AF]" />
              </button>
            )}

            {filter.type !== 'ALL' && (
              <button
                type="button"
                onClick={() => setFilter((prev) => ({ ...prev, type: 'ALL' }))}
                className="min-h-[34px] px-3 py-1 rounded-lg bg-[#181C22] border border-white/10 text-xs text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <span>Type: {filter.type === 'EXPENSE' ? 'Expenses' : 'Income'}</span>
                <X className="w-3.5 h-3.5 text-[#9CA3AF]" />
              </button>
            )}

            {(filter.datePreset !== 'ALL' || filter.startDate || filter.endDate) && (
              <button
                type="button"
                onClick={() =>
                  setFilter((prev) => ({
                    ...prev,
                    datePreset: 'ALL',
                    startDate: '',
                    endDate: '',
                  }))
                }
                className="min-h-[34px] px-3 py-1 rounded-lg bg-[#181C22] border border-white/10 text-xs font-mono-num text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <span>
                  Date: {filter.startDate || '...'} to {filter.endDate || '...'}
                </span>
                <X className="w-3.5 h-3.5 text-[#9CA3AF]" />
              </button>
            )}

            {filter.categories.map((cat) => (
              <button
                key={`active-cat-${cat}`}
                type="button"
                onClick={() => toggleCategory(cat)}
                className="min-h-[34px] px-3 py-1 rounded-lg bg-[#C6FF33]/12 border border-[#C6FF33]/35 text-xs text-[#C6FF33] hover:bg-[#C6FF33]/20 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <span>{cat}</span>
                <X className="w-3.5 h-3.5" />
              </button>
            ))}

            {filter.paymentMethods.map((method) => (
              <button
                key={`active-method-${method}`}
                type="button"
                onClick={() => togglePaymentMethod(method)}
                className="min-h-[34px] px-3 py-1 rounded-lg bg-[#181C22] border border-white/15 text-xs text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <span>{method}</span>
                <X className="w-3.5 h-3.5 text-[#9CA3AF]" />
              </button>
            ))}
          </div>
        )}

        {/* Live Filtered Totals Bar */}
        <div className="pt-3 border-t border-white/10 flex flex-col sm:flex-row sm:items-center justify-between gap-2 text-xs">
          <div className="text-[#9CA3AF] font-mono-num">
            Showing <strong className="text-[#F3F5F7]">{filtered.length}</strong> of{' '}
            <strong className="text-[#F3F5F7]">{entries.length}</strong> transactions
            {cloudQueryStatus && (
              <span className="ml-2 text-[#C6FF33]">· {cloudQueryStatus}</span>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-4 font-mono-num">
            <span className="text-[#FF6B2C]">
              Expenses: {formatMoney(expenseTotal, currencySymbol)}
            </span>
            <span className="text-[#2CE59B]">
              Income: {formatMoney(incomeTotal, currencySymbol)}
            </span>
            <span className="text-[#F3F5F7] font-semibold">
              Net: {formatMoney(netTotal, currencySymbol)}
            </span>
          </div>
        </div>
      </div>

      {/* Date-Wise Grouped Ledger */}
      {groupedByDate.length === 0 ? (
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-12 text-center space-y-4">
          <p className="text-sm text-[#9CA3AF]">
            {hasAnyActiveFilter
              ? 'No transactions match your combined filters.'
              : 'No transactions recorded yet. Add an expense, add balance, or import a receipt.'}
          </p>
          {hasAnyActiveFilter && (
            <button
              type="button"
              onClick={handleClearAll}
              className="min-h-[44px] px-5 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] inline-flex items-center gap-2 cursor-pointer"
            >
              <RotateCcw className="w-4 h-4" />
              <span>Reset All Filters</span>
            </button>
          )}
        </div>
      ) : (
        <div className="space-y-5">
          {groupedByDate.map(([date, dayEntries]) => (
            <div key={date} className="space-y-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-mono-num font-semibold text-[#C6FF33]">
                  {date}
                </span>
                <span className="text-xs text-[#9CA3AF] font-mono-num">
                  {dayEntries.length} {dayEntries.length === 1 ? 'entry' : 'entries'}
                </span>
              </div>

              <div className="bg-[#121519] border border-white/10 rounded-2xl divide-y divide-white/8">
                {dayEntries.map((entry) => {
                  const tx = entry.transaction;
                  const isExpense = tx.type === 'EXPENSE';
                  return (
                    <div
                      key={tx.id}
                      className="p-4 flex items-center justify-between gap-4 hover:bg-white/[0.02] transition-colors"
                    >
                      <button
                        type="button"
                        onClick={() => onEditTransaction(tx)}
                        className="min-w-0 flex-1 text-left cursor-pointer"
                      >
                        <p className="text-sm font-medium text-[#F3F5F7] truncate">
                          {tx.description}
                        </p>
                        <p className="text-xs text-[#9CA3AF] mt-0.5 truncate">
                          {formatCategoryLabel(tx.category, tx.customCategoryDetail)} · {tx.transactionTime} ·{' '}
                          {tx.paymentMethod || tx.sourceOfMoney || 'Manual'}
                          {tx.referenceId ? ` · Ref ${tx.referenceId}` : ''}
                        </p>
                      </button>

                      <div className="flex items-center gap-4 shrink-0">
                        <div className="text-right">
                          <p
                            className={`text-sm font-semibold font-mono-num ${
                              isExpense ? 'text-[#FF6B2C]' : 'text-[#2CE59B]'
                            }`}
                          >
                            {isExpense ? '-' : '+'}
                            {formatMoney(tx.amount, currencySymbol)}
                          </p>
                          <p className="text-xs text-[#9CA3AF] font-mono-num">
                            Bal {formatMoney(entry.runningBalanceAfter, currencySymbol)}
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => setDeleteTarget(tx)}
                          aria-label={`Delete ${tx.description}`}
                          className="min-h-[40px] min-w-[40px] rounded-xl flex items-center justify-center text-[#9CA3AF] hover:text-[#EF4444] hover:bg-[#EF4444]/10 transition-colors cursor-pointer"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Confirmation Modal */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-sm bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Delete Transaction?
            </h2>
            <p className="text-xs text-[#9CA3AF] leading-relaxed">
              Delete <strong className="text-[#F3F5F7]">{deleteTarget.description}</strong> ({formatMoney(deleteTarget.amount, currencySymbol)})? All subsequent running balances will be recalculated automatically.
            </p>
            <div className="flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={() => setDeleteTarget(null)}
                className="min-h-[40px] px-4 py-2 rounded-xl bg-[#181C22] text-xs font-medium text-[#F3F5F7] cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  onDeleteTransaction(deleteTarget.id);
                  setDeleteTarget(null);
                }}
                className="min-h-[40px] px-4 py-2 rounded-xl bg-[#EF4444] text-white text-xs font-semibold cursor-pointer"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
