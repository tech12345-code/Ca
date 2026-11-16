import React from 'react';
import { AlertTriangle } from 'lucide-react';
import { BalanceSummary, BudgetStatusColor } from '../types/expense';
import { formatMoney } from '../lib/ledgerEngine';

interface BudgetProgressBarProps {
  summary: BalanceSummary;
  currencySymbol: string;
  compact?: boolean;
}

export function getBudgetBarColors(status: BudgetStatusColor): {
  barBg: string;
  text: string;
} {
  switch (status) {
    case 'GREEN':
      return { barBg: 'bg-[#2CE59B]', text: 'text-[#2CE59B]' };
    case 'YELLOW':
      return { barBg: 'bg-[#FACC15]', text: 'text-[#FACC15]' };
    case 'RED':
      return { barBg: 'bg-[#EF4444]', text: 'text-[#EF4444]' };
  }
}

export const BudgetProgressBar: React.FC<BudgetProgressBarProps> = ({
  summary,
  currencySymbol,
  compact = false,
}) => {
  const colors = getBudgetBarColors(summary.budgetStatusColor);
  const visualFill =
    summary.monthlyBudget > 0
      ? Math.min(Math.max(summary.budgetUsagePercent, 0), 100)
      : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-2">
        <span className="text-[#9CA3AF]">Monthly Budget Usage</span>
        <span
          className={`font-mono-num font-bold inline-flex items-center gap-1.5 transition-colors duration-300 ${colors.text}`}
        >
          {summary.isBudgetExceeded && <AlertTriangle className="w-3.5 h-3.5" />}
          {summary.budgetUsagePercent.toFixed(1)}%
        </span>
      </div>

      <div className="flex items-center gap-3">
        <div className="flex-1 h-2.5 bg-[#1B2028] rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ease-out ${colors.barBg}`}
            style={{ width: `${visualFill}%` }}
          />
        </div>
        <span
          className={`text-xs font-mono-num font-semibold shrink-0 transition-colors duration-300 ${colors.text}`}
        >
          {summary.budgetUsagePercent.toFixed(1)}%
        </span>
      </div>

      <div className={`mt-4 grid ${compact ? 'grid-cols-2 gap-3' : 'grid-cols-3 gap-4'}`}>
        {!compact && (
          <div>
            <p className="text-xs text-[#9CA3AF]">Monthly Budget</p>
            <p className="mt-1 text-base font-semibold font-mono-num text-[#F3F5F7]">
              {formatMoney(summary.monthlyBudget, currencySymbol)}
            </p>
          </div>
        )}
        <div>
          <p className="text-xs text-[#9CA3AF]">Total Spent (Month)</p>
          <p className="mt-1 text-base font-semibold font-mono-num text-[#FF6B2C]">
            {formatMoney(summary.monthlySpent, currencySymbol)}
          </p>
        </div>
        <div>
          <p className="text-xs text-[#9CA3AF]">Remaining Budget</p>
          <p
            className={`mt-1 text-base font-semibold font-mono-num ${
              summary.remainingBudget < 0 ? 'text-[#EF4444]' : 'text-[#2CE59B]'
            }`}
          >
            {formatMoney(summary.remainingBudget, currencySymbol)}
          </p>
        </div>
      </div>

      {summary.isBudgetExceeded && (
        <div className="mt-3 px-3.5 py-2.5 rounded-xl bg-[#EF4444]/15 border border-[#EF4444]/40 flex items-center gap-2 text-xs font-semibold text-[#FCA5A5]">
          <AlertTriangle className="w-4 h-4 text-[#EF4444] shrink-0" />
          <span>Monthly Budget Exceeded!</span>
        </div>
      )}
    </div>
  );
};
