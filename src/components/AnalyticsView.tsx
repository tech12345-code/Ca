import React, { useMemo } from 'react';
import {
  AppRoute,
  BalanceSummary,
  Transaction,
} from '../types/expense';
import {
  computeCategoryBreakdown,
  computeMonthlySeries,
  computeOtherCategoryBreakdown,
  computeWeeklyDailySeries,
  formatMoney,
} from '../lib/ledgerEngine';
import { BudgetProgressBar, getBudgetBarColors } from './BudgetProgressBar';

interface AnalyticsViewProps {
  currencySymbol: string;
  summary: BalanceSummary;
  transactions: Transaction[];
  onNavigate: (route: AppRoute) => void;
}

export const AnalyticsView: React.FC<AnalyticsViewProps> = ({
  currencySymbol,
  summary,
  transactions,
  onNavigate,
}) => {
  const nowDate = new Date();
  const currentMonth = `${nowDate.getFullYear()}-${String(nowDate.getMonth() + 1).padStart(2, '0')}`;
  const prevDate = new Date(nowDate.getFullYear(), nowDate.getMonth() - 1, 1);
  const prevMonth = `${prevDate.getFullYear()}-${String(prevDate.getMonth() + 1).padStart(2, '0')}`;
  const budgetColors = getBudgetBarColors(summary.budgetStatusColor);

  const categoryBreakdown = useMemo(
    () => computeCategoryBreakdown(transactions),
    [transactions]
  );
  const otherBreakdown = useMemo(
    () => computeOtherCategoryBreakdown(transactions),
    [transactions]
  );
  const monthlySeries = useMemo(
    () => computeMonthlySeries(transactions, 6),
    [transactions]
  );
  const weeklySeries = useMemo(
    () => computeWeeklyDailySeries(transactions),
    [transactions]
  );

  const currentMonthExpense =
    monthlySeries.find((m) => m.month === currentMonth)?.expense || 0;
  const prevMonthExpense =
    monthlySeries.find((m) => m.month === prevMonth)?.expense || 0;
  const monthDelta = currentMonthExpense - prevMonthExpense;
  const topCategory = categoryBreakdown[0] || null;

  const maxMonthlyVal = Math.max(
    1,
    ...monthlySeries.map((m) => Math.max(m.income, m.expense))
  );
  const maxWeeklyVal = Math.max(1, ...weeklySeries.map((d) => d.expense));

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
            Analytics &amp; Reports
          </h1>
          <p className="text-sm text-[#9CA3AF] mt-1">
            Real-time spending trends, category distributions, and monthly comparisons computed from your ledger.
          </p>
        </div>
        <button
          type="button"
          onClick={() => onNavigate('pdf')}
          className="min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-xs hover:bg-[#d4ff66] cursor-pointer whitespace-nowrap"
        >
          Export PDF Statement
        </button>
      </div>

      {/* Top KPI Row */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
          <p className="text-xs text-[#9CA3AF]">Highest Spending Category</p>
          <p className="mt-1.5 text-xl font-bold text-[#C6FF33]">
            {topCategory ? topCategory.category : 'None'}
          </p>
          <p className="mt-1 text-xs font-mono-num text-[#9CA3AF]">
            {topCategory
              ? `${formatMoney(topCategory.total, currencySymbol)} (${topCategory.percent}%)`
              : 'No expenses yet'}
          </p>
        </div>

        <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
          <p className="text-xs text-[#9CA3AF]">Budget Usage</p>
          <p className={`mt-1.5 text-xl font-bold font-mono-num ${budgetColors.text}`}>
            {summary.budgetUsagePercent.toFixed(1)}%
          </p>
          <p className="mt-1 text-xs font-mono-num text-[#9CA3AF]">
            {formatMoney(summary.monthlySpent, currencySymbol)} of{' '}
            {formatMoney(summary.monthlyBudget, currencySymbol)}
          </p>
        </div>

        <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
          <p className="text-xs text-[#9CA3AF]">Monthly Comparison</p>
          <p
            className={`mt-1.5 text-xl font-bold font-mono-num ${
              monthDelta > 0 ? 'text-[#FF6B2C]' : 'text-[#2CE59B]'
            }`}
          >
            {monthDelta >= 0 ? '+' : ''}
            {formatMoney(monthDelta, currencySymbol)}
          </p>
          <p className="mt-1 text-xs text-[#9CA3AF]">
            vs previous month ({formatMoney(prevMonthExpense, currencySymbol)})
          </p>
        </div>

        <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
          <p className="text-xs text-[#9CA3AF]">Net Flow (Income - Expense)</p>
          <p
            className={`mt-1.5 text-xl font-bold font-mono-num ${
              summary.totalIncome - summary.totalExpenses >= 0
                ? 'text-[#2CE59B]'
                : 'text-[#FF6B2C]'
            }`}
          >
            {formatMoney(summary.totalIncome - summary.totalExpenses, currencySymbol)}
          </p>
          <p className="mt-1 text-xs text-[#9CA3AF]">
            Across {transactions.length} transactions
          </p>
        </div>
      </div>

      {/* Dynamic Monthly Budget Progress Bar (Identical Rules to Dashboard) */}
      <div className="bg-[#121519] border border-white/10 rounded-2xl p-6">
        <BudgetProgressBar summary={summary} currencySymbol={currencySymbol} />
      </div>

      {/* Monthly Income vs Expense & Weekly Overview */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        <div className="lg:col-span-7 bg-[#121519] border border-white/10 rounded-2xl p-6">
          <div className="flex items-center justify-between mb-6">
            <div>
              <h2 className="text-base font-semibold text-[#F3F5F7]">
                Monthly Income vs. Expenses
              </h2>
              <p className="text-xs text-[#9CA3AF]">Last 6 months comparison</p>
            </div>
            <div className="flex items-center gap-4 text-xs">
              <span className="text-[#2CE59B]">Income</span>
              <span className="text-[#FF6B2C]">Expense</span>
            </div>
          </div>

          <div className="h-56 flex items-end justify-between gap-3 pt-4">
            {monthlySeries.map((item) => {
              const incHeight = Math.round((item.income / maxMonthlyVal) * 160);
              const expHeight = Math.round((item.expense / maxMonthlyVal) * 160);
              return (
                <div
                  key={item.month}
                  className="flex-1 flex flex-col items-center gap-2"
                >
                  <div className="w-full h-44 flex items-end justify-center gap-1.5">
                    <div
                      title={`Income: ${formatMoney(item.income, currencySymbol)}`}
                      className="w-4 sm:w-5 rounded-t-md bg-[#2CE59B] transition-all"
                      style={{ height: `${Math.max(incHeight, item.income > 0 ? 6 : 2)}px` }}
                    />
                    <div
                      title={`Expense: ${formatMoney(item.expense, currencySymbol)}`}
                      className="w-4 sm:w-5 rounded-t-md bg-[#FF6B2C] transition-all"
                      style={{ height: `${Math.max(expHeight, item.expense > 0 ? 6 : 2)}px` }}
                    />
                  </div>
                  <span className="text-xs font-mono-num text-[#9CA3AF]">
                    {item.label}
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        <div className="lg:col-span-5 bg-[#121519] border border-white/10 rounded-2xl p-6">
          <h2 className="text-base font-semibold text-[#F3F5F7]">
            Weekly Spending Overview
          </h2>
          <p className="text-xs text-[#9CA3AF] mb-6">Last 7 days daily expenditure</p>

          <div className="h-56 flex items-end justify-between gap-2 pt-4">
            {weeklySeries.map((day) => {
              const h = Math.round((day.expense / maxWeeklyVal) * 155);
              return (
                <div
                  key={day.date}
                  className="flex-1 flex flex-col items-center gap-2"
                >
                  <div className="w-full h-44 flex items-end justify-center">
                    <div
                      title={`${day.date}: ${formatMoney(day.expense, currencySymbol)}`}
                      className="w-6 rounded-t-md bg-[#C6FF33] transition-all"
                      style={{ height: `${Math.max(h, day.expense > 0 ? 6 : 2)}px` }}
                    />
                  </div>
                  <span className="text-xs font-mono-num text-[#9CA3AF]">
                    {day.label}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Category-Wise Spending Breakdown */}
      <div className="bg-[#121519] border border-white/10 rounded-2xl p-6">
        <h2 className="text-base font-semibold text-[#F3F5F7] mb-4">
          Category-Wise Spending
        </h2>
        {categoryBreakdown.length === 0 ? (
          <p className="text-sm text-[#9CA3AF] py-6">
            Add expense entries to populate your category-wise spending chart.
          </p>
        ) : (
          <div className="space-y-4">
            {categoryBreakdown.map((item) => (
              <div key={item.category} className="space-y-1.5">
                <div className="flex items-center justify-between text-sm">
                  <span className="text-[#F3F5F7] font-medium">
                    {item.category}
                  </span>
                  <span className="font-mono-num text-[#F3F5F7]">
                    {formatMoney(item.total, currencySymbol)}{' '}
                    <span className="text-xs text-[#9CA3AF]">({item.percent}%)</span>
                  </span>
                </div>
                <div className="w-full h-2 bg-[#181C22] rounded-full overflow-hidden">
                  <div
                    className="h-full bg-[#C6FF33] rounded-full"
                    style={{ width: `${Math.max(item.percent, 2)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Custom "Other" Category Breakdown */}
      {otherBreakdown.length > 0 && (
        <div className="bg-[#121519] border border-white/10 rounded-2xl p-6">
          <div className="flex items-center justify-between gap-4 mb-4">
            <div>
              <h2 className="text-base font-semibold text-[#F3F5F7]">
                Other Category — Custom Spend Breakdown
              </h2>
              <p className="text-xs text-[#9CA3AF] mt-0.5">
                Itemized breakdown of custom &ldquo;Other&rdquo; expense descriptions.
              </p>
            </div>
            <span className="text-xs font-mono-num text-[#C6FF33]">
              {otherBreakdown.length} {otherBreakdown.length === 1 ? 'item' : 'items'}
            </span>
          </div>
          <div className="space-y-4">
            {otherBreakdown.map((item) => (
              <div key={item.label} className="space-y-1.5">
                <div className="flex items-center justify-between text-sm">
                  <span className="text-[#F3F5F7] font-medium">
                    Other — {item.label}{' '}
                    <span className="text-xs text-[#9CA3AF] font-mono-num">
                      · {item.count} {item.count === 1 ? 'entry' : 'entries'}
                    </span>
                  </span>
                  <span className="font-mono-num text-[#FF6B2C]">
                    {formatMoney(item.total, currencySymbol)}{' '}
                    <span className="text-xs text-[#9CA3AF]">({item.percent}%)</span>
                  </span>
                </div>
                <div className="w-full h-2 bg-[#181C22] rounded-full overflow-hidden">
                  <div
                    className="h-full bg-[#FF6B2C] rounded-full"
                    style={{ width: `${Math.max(item.percent, 2)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
