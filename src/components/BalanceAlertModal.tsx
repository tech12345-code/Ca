import React from 'react';
import { AlertTriangle, AlertCircle } from 'lucide-react';
import { BalanceValidationResult } from '../types/expense';
import { formatMoney } from '../lib/ledgerEngine';

interface InsufficientBalanceModalProps {
  currencySymbol: string;
  validation: BalanceValidationResult;
  onAddBalance: () => void;
  onDismiss: () => void;
}

export const InsufficientBalanceModal: React.FC<InsufficientBalanceModalProps> = ({
  currencySymbol,
  validation,
  onAddBalance,
  onDismiss,
}) => {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="insufficient-balance-title"
      className="fixed inset-0 z-[60] bg-black/80 backdrop-blur-sm flex items-center justify-center p-4"
    >
      <div className="w-full max-w-md bg-[#121519] border border-[#EF4444]/40 rounded-2xl p-6 shadow-2xl space-y-4">
        <div className="flex items-start gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#EF4444]/15 border border-[#EF4444]/30 flex items-center justify-center shrink-0">
            <AlertTriangle className="w-5 h-5 text-[#EF4444]" />
          </div>
          <div>
            <h3
              id="insufficient-balance-title"
              className="text-lg font-bold text-[#EF4444]"
            >
              {validation.errorTitle || 'Insufficient Balance!'}
            </h3>
            <p className="text-sm text-[#F3F5F7] mt-1">
              {validation.errorMessage ||
                "You don't have enough money to complete this expense."}
            </p>
          </div>
        </div>

        <div className="rounded-xl bg-[#0B0D0F] border border-white/10 p-4 space-y-2.5">
          <div className="flex items-center justify-between text-xs">
            <span className="text-[#9CA3AF]">Current Available Balance</span>
            <span className="font-mono-num font-bold text-sm text-[#C6FF33]">
              {formatMoney(validation.currentAvailableBalance, currencySymbol)}
            </span>
          </div>
          <div className="flex items-center justify-between text-xs pt-2 border-t border-white/8">
            <span className="text-[#9CA3AF]">Additional Amount Required</span>
            <span className="font-mono-num font-bold text-sm text-[#EF4444]">
              {formatMoney(validation.shortfallAmount, currencySymbol)}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-3 pt-1">
          <button
            type="button"
            onClick={onDismiss}
            className="flex-1 min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-sm font-medium text-[#F3F5F7] hover:bg-[#20262e] cursor-pointer"
          >
            Adjust Amount
          </button>
          <button
            type="button"
            onClick={onAddBalance}
            className="flex-1 min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
          >
            Add Balance
          </button>
        </div>
      </div>
    </div>
  );
};

interface BalanceExhaustedModalProps {
  onDismiss: () => void;
}

export const BalanceExhaustedModal: React.FC<BalanceExhaustedModalProps> = ({
  onDismiss,
}) => {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="balance-exhausted-title"
      className="fixed inset-0 z-[60] bg-black/80 backdrop-blur-sm flex items-center justify-center p-4"
    >
      <div className="w-full max-w-md bg-[#121519] border border-[#FF6B2C]/40 rounded-2xl p-6 shadow-2xl space-y-4">
        <div className="flex items-start gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#FF6B2C]/15 border border-[#FF6B2C]/30 flex items-center justify-center shrink-0">
            <AlertCircle className="w-5 h-5 text-[#FF6B2C]" />
          </div>
          <div>
            <h3
              id="balance-exhausted-title"
              className="text-lg font-bold text-[#FF6B2C]"
            >
              Balance Exhausted!
            </h3>
            <p className="text-sm text-[#F3F5F7] mt-1">
              You have used your entire available balance.
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={onDismiss}
          className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
        >
          OK
        </button>
      </div>
    </div>
  );
};
