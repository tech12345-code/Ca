import React, { useState } from 'react';
import { X, Trash2 } from 'lucide-react';
import {
  EXPENSE_CATEGORIES,
  ExpenseCategory,
  Transaction,
  TransactionType,
} from '../types/expense';

interface TransactionModalProps {
  initialType: TransactionType;
  presetCategory?: ExpenseCategory;
  existing?: Transaction | null;
  currencySymbol: string;
  onClose: () => void;
  onSave: (data: {
    id?: string;
    type: TransactionType;
    amount: number;
    description: string;
    merchant: string;
    category: ExpenseCategory;
    customCategoryDetail: string;
    paymentMethod: string;
    sourceOfMoney: string;
    note: string;
    transactionDate: string;
    transactionTime: string;
    referenceId: string;
    invoiceNumber: string;
  }) => string | null;
  onDelete?: (id: string) => void;
}

export const TransactionModal: React.FC<TransactionModalProps> = ({
  initialType,
  presetCategory,
  existing,
  currencySymbol,
  onClose,
  onSave,
  onDelete,
}) => {
  const now = new Date();
  const defaultDate = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  const defaultTime = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;

  const initialCategory: ExpenseCategory =
    existing?.category || presetCategory || 'Food';

  const [type, setType] = useState<TransactionType>(existing?.type || initialType);
  const [amount, setAmount] = useState(existing ? String(existing.amount) : '');
  const [description, setDescription] = useState(existing?.description || '');
  const [merchant, setMerchant] = useState(existing?.merchant || '');
  const [category, setCategory] = useState<ExpenseCategory>(initialCategory);
  const [customCategoryDetail, setCustomCategoryDetail] = useState(
    initialCategory === 'Other'
      ? existing?.customCategoryDetail || existing?.description || ''
      : ''
  );
  const [paymentMethod, setPaymentMethod] = useState(existing?.paymentMethod || 'UPI');
  const [sourceOfMoney, setSourceOfMoney] = useState(existing?.sourceOfMoney || 'Salary / Bank');
  const [transactionDate, setTransactionDate] = useState(existing?.transactionDate || defaultDate);
  const [transactionTime, setTransactionTime] = useState(existing?.transactionTime || defaultTime);
  const [referenceId, setReferenceId] = useState(existing?.referenceId || '');
  const [note, setNote] = useState(existing?.note || '');
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const handleCategorySelect = (nextCat: ExpenseCategory) => {
    if (nextCat !== 'Other') {
      setCustomCategoryDetail('');
    }
    setCategory(nextCat);
    if (error) setError(null);
  };

  const handleTypeSelect = (nextType: TransactionType) => {
    if (nextType === 'INCOME') {
      setCustomCategoryDetail('');
    }
    setType(nextType);
    if (error) setError(null);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setError('Enter a valid amount greater than zero.');
      return;
    }

    const trimmedDetail =
      type === 'EXPENSE' && category === 'Other' ? customCategoryDetail.trim() : '';

    if (type === 'EXPENSE' && category === 'Other' && !trimmedDetail) {
      setError('Please enter where you spent this money for the Other category.');
      return;
    }

    const resolvedDescription = description.trim() || trimmedDetail;
    if (!resolvedDescription) {
      setError('Description is required.');
      return;
    }

    if (!/^\d{4}-\d{2}-\d{2}$/.test(transactionDate)) {
      setError('Select a valid transaction date.');
      return;
    }

    const err = onSave({
      id: existing?.id,
      type,
      amount: parsed,
      description: resolvedDescription,
      merchant: merchant.trim() || resolvedDescription,
      category: type === 'INCOME' ? 'Income' : category,
      customCategoryDetail: trimmedDetail,
      paymentMethod: type === 'EXPENSE' ? paymentMethod : '',
      sourceOfMoney: type === 'INCOME' ? sourceOfMoney : '',
      note: note.trim(),
      transactionDate,
      transactionTime,
      referenceId: referenceId.trim(),
      invoiceNumber: existing?.invoiceNumber || '',
    });
    if (err) {
      setError(err);
    } else {
      onClose();
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="w-full sm:max-w-lg bg-[#121519] border border-white/10 rounded-t-3xl sm:rounded-2xl max-h-[92vh] overflow-y-auto p-6">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold text-[#F3F5F7]">
            {existing ? 'Edit Transaction' : type === 'EXPENSE' ? 'Add Expense' : 'Add Balance'}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="min-h-[40px] min-w-[40px] rounded-xl bg-[#181C22] flex items-center justify-center text-[#9CA3AF] hover:text-white cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-2 gap-1 p-1 bg-[#0B0D0F] rounded-xl border border-white/8">
            <button
              type="button"
              onClick={() => handleTypeSelect('EXPENSE')}
              className={`min-h-[40px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                type === 'EXPENSE'
                  ? 'bg-[#FF6B2C] text-white'
                  : 'text-[#9CA3AF] hover:text-white'
              }`}
            >
              Expense
            </button>
            <button
              type="button"
              onClick={() => handleTypeSelect('INCOME')}
              className={`min-h-[40px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
                type === 'INCOME'
                  ? 'bg-[#C6FF33] text-[#0B0D0F]'
                  : 'text-[#9CA3AF] hover:text-white'
              }`}
            >
              Add Balance
            </button>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              Amount ({currencySymbol})
            </label>
            <input
              type="number"
              step="0.01"
              min="0.01"
              required
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="0.00"
              className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-lg font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              Description {type === 'EXPENSE' && category === 'Other' ? '(Optional if specified below)' : ''}
            </label>
            <input
              type="text"
              required={!(type === 'EXPENSE' && category === 'Other')}
              maxLength={200}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={type === 'EXPENSE' ? 'e.g. Dinner at Spice Garden' : 'e.g. Monthly Salary'}
              className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
          </div>

          {type === 'EXPENSE' ? (
            <>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Category
                </label>
                <div className="grid grid-cols-3 gap-2">
                  {EXPENSE_CATEGORIES.map((cat) => (
                    <button
                      key={cat}
                      type="button"
                      onClick={() => handleCategorySelect(cat)}
                      className={`min-h-[38px] px-2.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer ${
                        category === cat
                          ? 'bg-[#C6FF33] text-[#0B0D0F] font-semibold'
                          : 'bg-[#181C22] border border-white/8 text-[#9CA3AF] hover:text-white'
                      }`}
                    >
                      {cat}
                    </button>
                  ))}
                </div>
              </div>

              {category === 'Other' && (
                <div className="rounded-xl bg-[#0B0D0F] border border-[#C6FF33]/45 p-3.5 space-y-2 transition-opacity">
                  <label
                    htmlFor="other-expense-detail"
                    className="block text-xs font-semibold text-[#C6FF33]"
                  >
                    Where have you spent this money? *
                  </label>
                  <input
                    id="other-expense-detail"
                    type="text"
                    required
                    maxLength={120}
                    value={customCategoryDetail}
                    onChange={(e) => {
                      setCustomCategoryDetail(e.target.value);
                      if (error) setError(null);
                    }}
                    placeholder="Enter expense details (e.g. Mobile recharge, Movie ticket, Gift...)"
                    className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#121519] border border-[#C6FF33]/35 text-sm text-[#F3F5F7] placeholder:text-[#6B7280] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              )}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Payment Method
                  </label>
                  <select
                    value={paymentMethod}
                    onChange={(e) => setPaymentMethod(e.target.value)}
                    className="w-full min-h-[44px] px-3 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  >
                    <option value="UPI">UPI</option>
                    <option value="Google Pay (UPI)">Google Pay (UPI)</option>
                    <option value="PhonePe (UPI)">PhonePe (UPI)</option>
                    <option value="Paytm (UPI)">Paytm (UPI)</option>
                    <option value="Debit Card">Debit Card</option>
                    <option value="Credit Card">Credit Card</option>
                    <option value="Cash">Cash</option>
                    <option value="Net Banking">Net Banking</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Merchant (Optional)
                  </label>
                  <input
                    type="text"
                    maxLength={120}
                    value={merchant}
                    onChange={(e) => setMerchant(e.target.value)}
                    placeholder="Merchant name"
                    className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              </div>
            </>
          ) : (
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Source of Money
              </label>
              <input
                type="text"
                maxLength={80}
                value={sourceOfMoney}
                onChange={(e) => setSourceOfMoney(e.target.value)}
                placeholder="e.g. Salary, Freelance, Bank Transfer, Refund"
                className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Transaction Date
              </label>
              <input
                type="date"
                required
                value={transactionDate}
                onChange={(e) => setTransactionDate(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Transaction Time
              </label>
              <input
                type="time"
                required
                value={transactionTime}
                onChange={(e) => setTransactionTime(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              Reference ID (Optional duplicate guard)
            </label>
            <input
              type="text"
              maxLength={120}
              value={referenceId}
              onChange={(e) => setReferenceId(e.target.value)}
              placeholder="UPI Ref / UTR / Invoice Ref"
              className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
              Optional Note
            </label>
            <textarea
              rows={2}
              maxLength={500}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Add extra context..."
              className="w-full px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
          </div>

          {error && <p className="text-xs text-[#EF4444]">{error}</p>}

          <div className="flex items-center gap-3 pt-2">
            {existing && onDelete && (
              <button
                type="button"
                onClick={() => {
                  if (confirmDelete) {
                    onDelete(existing.id);
                    onClose();
                  } else {
                    setConfirmDelete(true);
                  }
                }}
                className="min-h-[46px] px-4 py-2.5 rounded-xl bg-[#EF4444]/15 border border-[#EF4444]/30 text-[#FCA5A5] text-sm font-medium inline-flex items-center gap-1.5 cursor-pointer"
              >
                <Trash2 className="w-4 h-4" />
                <span>{confirmDelete ? 'Confirm Delete' : 'Delete'}</span>
              </button>
            )}
            <button
              type="submit"
              className={`flex-1 min-h-[46px] px-5 py-2.5 rounded-xl font-semibold text-sm cursor-pointer ${
                type === 'EXPENSE'
                  ? 'bg-[#FF6B2C] text-white hover:bg-[#ff7f47]'
                  : 'bg-[#C6FF33] text-[#0B0D0F] hover:bg-[#d4ff66]'
              }`}
            >
              {existing ? 'Save Changes' : type === 'EXPENSE' ? 'Save Expense' : 'Save Balance'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
