import React from 'react';
import {
  Plus,
  ArrowDownLeft,
  ScanLine,
  ChevronRight,
  AlertCircle,
} from 'lucide-react';
import {
  AppRoute,
  BalanceSummary,
  EXPENSE_CATEGORIES,
  ExpenseCategory,
  Transaction,
  TransactionType,
  UserProfile,
} from '../types/expense';
import { formatCategoryLabel, formatMoney } from '../lib/ledgerEngine';
import { BudgetProgressBar } from './BudgetProgressBar';

interface DashboardViewProps {
  profile: UserProfile;
  summary: BalanceSummary;
  onAddTransaction: (type: TransactionType, presetCategory?: ExpenseCategory) => void;
  onEditTransaction: (tx: Transaction) => void;
  onNavigate: (route: AppRoute) => void;
}

export const DashboardView: React.FC<DashboardViewProps> = ({
  profile,
  summary,
  onAddTransaction,
  onEditTransaction,
  onNavigate,
}) => {
  const c = profile.currencySymbol;
  const recent = [...summary.ledger].reverse().slice(0, 8);

  return (
    <div className="space-y-6">
      {/* Greeting & Quick Actions Header */}
      <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4">
        <div>
          <p className="text-xs text-[#9CA3AF]">
            {new Date().toLocaleDateString('en-US', {
              weekday: 'long',
              month: 'long',
              day: 'numeric',
              year: 'numeric',
            })}
          </p>
          <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7] mt-1">
            Hello, {profile.displayName}
          </h1>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={() => onAddTransaction('EXPENSE')}
            className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#FF6B2C] text-white font-semibold text-sm hover:bg-[#ff7f47] transition-colors inline-flex items-center gap-2 whitespace-nowrap cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Add Expense</span>
          </button>
          <button
            type="button"
            onClick={() => onAddTransaction('INCOME')}
            className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] transition-colors inline-flex items-center gap-2 whitespace-nowrap cursor-pointer"
          >
            <ArrowDownLeft className="w-4 h-4" />
            <span>Add Balance</span>
          </button>
          <button
            type="button"
            onClick={() => onNavigate('receipt')}
            className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-[#F3F5F7] font-medium text-sm hover:bg-[#20262e] transition-colors inline-flex items-center gap-2 whitespace-nowrap cursor-pointer"
          >
            <ScanLine className="w-4 h-4 text-[#C6FF33]" />
            <span>Import Receipt</span>
          </button>
        </div>
      </div>

      {/* Hero Financial Summary Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-stretch">
        {/* Available Balance & Budget Progress */}
        <div className="lg:col-span-7 bg-[#121519] border border-white/10 rounded-2xl p-6 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between gap-4">
              <span className="text-xs font-medium tracking-wider uppercase text-[#9CA3AF]">
                Total Available Balance
              </span>
              <span className="text-xs text-[#9CA3AF] font-mono-num">
                Opening {formatMoney(summary.openingBalance, c)} + Income {formatMoney(summary.totalIncome, c)} - Expenses {formatMoney(summary.totalExpenses, c)}
              </span>
            </div>
            <div
              className={`mt-2 text-4xl sm:text-5xl font-bold font-mono-num tracking-tight ${
                summary.isBalanceExhausted ? 'text-[#FF6B2C]' : 'text-[#C6FF33]'
              }`}
            >
              {formatMoney(summary.availableBalance, c)}
            </div>
            {summary.isBalanceExhausted && (
              <div className="mt-3 px-3.5 py-2.5 rounded-xl bg-[#FF6B2C]/15 border border-[#FF6B2C]/40 flex items-start gap-2.5">
                <AlertCircle className="w-4 h-4 text-[#FF6B2C] shrink-0 mt-0.5" />
                <div>
                  <p className="text-xs font-bold text-[#FF6B2C]">Balance Exhausted!</p>
                  <p className="text-xs text-[#F3F5F7]">
                    You have used your entire available balance.
                  </p>
                </div>
              </div>
            )}
          </div>

          <div className="mt-8 pt-6 border-t border-white/10">
            <BudgetProgressBar summary={summary} currencySymbol={c} />
          </div>
        </div>

        {/* Quick Expense Categories */}
        <div className="lg:col-span-5 bg-[#121519] border border-white/10 rounded-2xl p-6 flex flex-col justify-between">
          <div>
            <h2 className="text-base font-semibold text-[#F3F5F7]">Quick Expense Entry</h2>
            <p className="text-xs text-[#9CA3AF] mt-1">
              Select a category to record a transaction directly.
            </p>
            <div className="mt-4 grid grid-cols-3 gap-2.5">
              {EXPENSE_CATEGORIES.map((category) => (
                <button
                  key={category}
                  type="button"
                  onClick={() => onAddTransaction('EXPENSE', category)}
                  className="min-h-[44px] px-3 py-2.5 rounded-xl bg-[#181C22] border border-white/8 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/50 hover:text-[#C6FF33] transition-colors text-left truncate cursor-pointer"
                >
                  {category}
                </button>
              ))}
            </div>
          </div>

          <div className="mt-5 pt-4 border-t border-white/10 flex items-center justify-between">
            <span className="text-xs text-[#9CA3AF]">
              Chronological entries: <strong className="font-mono-num text-[#F3F5F7]">{summary.ledger.length}</strong>
            </span>
            <button
              type="button"
              onClick={() => onNavigate('analytics')}
              className="text-xs font-medium text-[#C6FF33] hover:underline inline-flex items-center gap-1 cursor-pointer"
            >
              <span>View Analytics</span>
              <ChevronRight className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      {/* Recent Transactions */}
      <div className="bg-[#121519] border border-white/10 rounded-2xl p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-semibold text-[#F3F5F7]">Recent Transactions</h2>
          <button
            type="button"
            onClick={() => onNavigate('ledger')}
            className="text-xs font-medium text-[#C6FF33] hover:underline inline-flex items-center gap-1 cursor-pointer"
          >
            <span>Full Ledger</span>
            <ChevronRight className="w-3.5 h-3.5" />
          </button>
        </div>

        {recent.length === 0 ? (
          <div className="py-10 text-center">
            <p className="text-sm text-[#9CA3AF]">
              No transactions recorded yet. Add an expense, add balance, or import a payment receipt to begin.
            </p>
            <button
              type="button"
              onClick={() => onAddTransaction('EXPENSE')}
              className="mt-4 min-h-[40px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#C6FF33] hover:bg-[#1f242c] cursor-pointer"
            >
              Log First Expense
            </button>
          </div>
        ) : (
          <div className="divide-y divide-white/8">
            {recent.map((entry) => {
              const tx = entry.transaction;
              const isExpense = tx.type === 'EXPENSE';
              return (
                <button
                  key={tx.id}
                  type="button"
                  onClick={() => onEditTransaction(tx)}
                  className="w-full py-3.5 flex items-center justify-between gap-4 text-left hover:bg-white/[0.02] px-2 -mx-2 rounded-xl transition-colors cursor-pointer"
                >
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-[#F3F5F7] truncate">
                      {tx.description}
                    </p>
                    <p className="text-xs text-[#9CA3AF] mt-0.5 truncate">
                      {formatCategoryLabel(tx.category, tx.customCategoryDetail)} · {tx.transactionDate} {tx.transactionTime} ·{' '}
                      {tx.paymentMethod || tx.sourceOfMoney || 'Manual'}
                    </p>
                  </div>
                  <div className="text-right shrink-0">
                    <p
                      className={`text-sm font-semibold font-mono-num ${
                        isExpense ? 'text-[#FF6B2C]' : 'text-[#2CE59B]'
                      }`}
                    >
                      {isExpense ? '-' : '+'}
                      {formatMoney(tx.amount, c)}
                    </p>
                    <p className="text-xs text-[#9CA3AF] font-mono-num mt-0.5">
                      Bal {formatMoney(entry.runningBalanceAfter, c)}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
