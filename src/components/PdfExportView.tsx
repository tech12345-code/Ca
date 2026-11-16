import React, { useMemo, useState } from 'react';
import { FileDown } from 'lucide-react';
import { BalanceSummary, UserProfile } from '../types/expense';
import { formatCategoryLabel, formatMoney } from '../lib/ledgerEngine';
import { generatePdfStatement } from '../lib/pdfExport';

interface PdfExportViewProps {
  profile: UserProfile;
  summary: BalanceSummary;
}

export const PdfExportView: React.FC<PdfExportViewProps> = ({ profile, summary }) => {
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  const monthStart = `${today.slice(0, 7)}-01`;
  const weekStartDate = new Date(Date.now() - 6 * 86400000);
  const weekStart = `${weekStartDate.getFullYear()}-${String(weekStartDate.getMonth() + 1).padStart(2, '0')}-${String(weekStartDate.getDate()).padStart(2, '0')}`;

  const [preset, setPreset] = useState<'monthly' | 'weekly' | 'custom'>('monthly');
  const [startDate, setStartDate] = useState(monthStart);
  const [endDate, setEndDate] = useState(today);
  const [exportedNote, setExportedNote] = useState<string | null>(null);

  const statementData = useMemo(() => {
    const beforeRange = summary.ledger.filter(
      (e) => e.transaction.transactionDate < startDate
    );
    const openingForRange =
      beforeRange.length > 0
        ? beforeRange[beforeRange.length - 1].runningBalanceAfter
        : summary.openingBalance;

    const inRange = summary.ledger.filter(
      (e) =>
        e.transaction.transactionDate >= startDate &&
        e.transaction.transactionDate <= endDate
    );

    let income = 0;
    let expense = 0;
    for (const e of inRange) {
      if (e.transaction.type === 'INCOME') income += e.transaction.amount;
      else expense += e.transaction.amount;
    }

    const closing =
      inRange.length > 0
        ? inRange[inRange.length - 1].runningBalanceAfter
        : openingForRange;

    return {
      openingForRange,
      income,
      expense,
      closing,
      inRange,
    };
  }, [summary, startDate, endDate]);

  const applyPreset = (mode: 'monthly' | 'weekly' | 'custom') => {
    setPreset(mode);
    if (mode === 'monthly') {
      setStartDate(monthStart);
      setEndDate(today);
    } else if (mode === 'weekly') {
      setStartDate(weekStart);
      setEndDate(today);
    }
  };

  const handleDownloadPdf = () => {
    generatePdfStatement({
      appName: 'Smart Expense Manager',
      userName: profile.displayName,
      userEmail: profile.email,
      currencySymbol: profile.currencySymbol,
      startDate,
      endDate,
      openingBalanceForRange: statementData.openingForRange,
      totalIncomeForRange: statementData.income,
      totalExpensesForRange: statementData.expense,
      closingBalanceForRange: statementData.closing,
      entries: statementData.inRange,
    });
    setExportedNote(
      `Downloaded SmartExpense_Statement_${startDate}_to_${endDate}.pdf`
    );
  };

  const c = profile.currencySymbol;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
          PDF Statement Export
        </h1>
        <p className="text-sm text-[#9CA3AF] mt-1">
          Generate formatted financial statements with opening balance, income, expenses, closing balance, and itemized running ledger.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Controls */}
        <div className="lg:col-span-4 bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
          <div className="grid grid-cols-3 gap-1 p-1 bg-[#0B0D0F] rounded-xl border border-white/8">
            {(['monthly', 'weekly', 'custom'] as const).map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => applyPreset(m)}
                className={`min-h-[38px] rounded-lg text-xs font-medium capitalize cursor-pointer ${
                  preset === m
                    ? 'bg-[#C6FF33] text-[#0B0D0F] font-semibold'
                    : 'text-[#9CA3AF] hover:text-white'
                }`}
              >
                {m}
              </button>
            ))}
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              Start Date
            </label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => {
                setPreset('custom');
                setStartDate(e.target.value);
              }}
              className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7]"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              End Date
            </label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => {
                setPreset('custom');
                setEndDate(e.target.value);
              }}
              className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7]"
            />
          </div>

          <button
            type="button"
            onClick={handleDownloadPdf}
            className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] inline-flex items-center justify-center gap-2 cursor-pointer"
          >
            <FileDown className="w-4 h-4" />
            <span>Download PDF Statement</span>
          </button>

          {exportedNote && (
            <p className="text-xs text-[#2CE59B]">{exportedNote}</p>
          )}
        </div>

        {/* Live Statement Preview */}
        <div className="lg:col-span-8 bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-5">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-4 border-b border-white/10">
            <div>
              <h2 className="text-lg font-semibold text-[#F3F5F7]">
                Smart Expense Manager — Statement Preview
              </h2>
              <p className="text-xs text-[#9CA3AF]">
                {profile.displayName} · {startDate} to {endDate}
              </p>
            </div>
            <span className="text-xs font-mono-num text-[#9CA3AF]">
              Generated {today}
            </span>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div>
              <p className="text-xs text-[#9CA3AF]">Opening Balance</p>
              <p className="mt-1 text-base font-semibold font-mono-num text-[#F3F5F7]">
                {formatMoney(statementData.openingForRange, c)}
              </p>
            </div>
            <div>
              <p className="text-xs text-[#9CA3AF]">Total Income</p>
              <p className="mt-1 text-base font-semibold font-mono-num text-[#2CE59B]">
                +{formatMoney(statementData.income, c)}
              </p>
            </div>
            <div>
              <p className="text-xs text-[#9CA3AF]">Total Expenses</p>
              <p className="mt-1 text-base font-semibold font-mono-num text-[#FF6B2C]">
                -{formatMoney(statementData.expense, c)}
              </p>
            </div>
            <div>
              <p className="text-xs text-[#9CA3AF]">Closing Balance</p>
              <p className="mt-1 text-base font-semibold font-mono-num text-[#C6FF33]">
                {formatMoney(statementData.closing, c)}
              </p>
            </div>
          </div>

          <div className="overflow-x-auto pt-2">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-white/10 text-[#9CA3AF]">
                  <th className="py-2.5 pr-3 font-medium">Date &amp; Time</th>
                  <th className="py-2.5 pr-3 font-medium">Description</th>
                  <th className="py-2.5 pr-3 font-medium">Category</th>
                  <th className="py-2.5 pr-3 font-medium text-right">Amount</th>
                  <th className="py-2.5 font-medium text-right">Balance</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/8">
                {statementData.inRange.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-[#9CA3AF]">
                      No transactions in this date range.
                    </td>
                  </tr>
                ) : (
                  statementData.inRange.map((entry) => {
                    const tx = entry.transaction;
                    const isExpense = tx.type === 'EXPENSE';
                    return (
                      <tr key={tx.id}>
                        <td className="py-2.5 pr-3 font-mono-num text-[#9CA3AF] whitespace-nowrap">
                          {tx.transactionDate} {tx.transactionTime}
                        </td>
                        <td className="py-2.5 pr-3 text-[#F3F5F7]">{tx.description}</td>
                        <td className="py-2.5 pr-3 text-[#9CA3AF]">
                          {formatCategoryLabel(tx.category, tx.customCategoryDetail)}
                        </td>
                        <td
                          className={`py-2.5 pr-3 font-mono-num text-right whitespace-nowrap ${
                            isExpense ? 'text-[#FF6B2C]' : 'text-[#2CE59B]'
                          }`}
                        >
                          {isExpense ? '-' : '+'}
                          {formatMoney(tx.amount, c)}
                        </td>
                        <td className="py-2.5 font-mono-num text-right text-[#F3F5F7] whitespace-nowrap">
                          {formatMoney(entry.runningBalanceAfter, c)}
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
};
