import React, { useRef, useState } from 'react';
import {
  Camera,
  Upload,
  FileText,
  AlertTriangle,
  CheckCircle2,
  Loader2,
} from 'lucide-react';
import {
  EXPENSE_CATEGORIES,
  ExpenseCategory,
  ExtractedReceiptDraft,
  Transaction,
} from '../types/expense';
import { findDuplicateTransaction } from '../lib/ledgerEngine';

interface ReceiptImportViewProps {
  currencySymbol: string;
  existingTransactions: Transaction[];
  onConfirmSave: (data: {
    amount: number;
    description: string;
    merchant: string;
    category: ExpenseCategory;
    customCategoryDetail: string;
    paymentMethod: string;
    note: string;
    transactionDate: string;
    transactionTime: string;
    referenceId: string;
    invoiceNumber: string;
  }) => string | null;
}

export const ReceiptImportView: React.FC<ReceiptImportViewProps> = ({
  currencySymbol,
  existingTransactions,
  onConfirmSave,
}) => {
  const imageInputRef = useRef<HTMLInputElement>(null);
  const cameraInputRef = useRef<HTMLInputElement>(null);
  const pdfInputRef = useRef<HTMLInputElement>(null);

  const [rawText, setRawText] = useState('');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [draft, setDraft] = useState<ExtractedReceiptDraft | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const runOcr = async (payload: {
    imageBase64?: string;
    mimeType?: string;
    rawText?: string;
  }) => {
    setScanning(true);
    setScanError(null);
    setSaveError(null);
    try {
      const resp = await fetch('/api/receipt-ocr', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await resp.json();
      if (!resp.ok) {
        throw new Error(data.error || 'Receipt extraction failed');
      }

      const missing: string[] = [];
      if (!data.merchantName) missing.push('Merchant Name');
      if (!data.amount) missing.push('Amount');
      if (!data.transactionDate) missing.push('Transaction Date');
      if (!data.transactionTime) missing.push('Transaction Time');
      if (!data.paymentMethod) missing.push('Payment Method');

      const parsedAmount = Number(data.amount);
      const dupReason = findDuplicateTransaction(
        {
          referenceId: data.referenceId,
          amount: Number.isFinite(parsedAmount) ? parsedAmount : undefined,
          date: data.transactionDate,
          merchant: data.merchantName,
        },
        existingTransactions
      );

      const category: ExpenseCategory = EXPENSE_CATEGORIES.includes(data.suggestedCategory)
        ? data.suggestedCategory
        : 'Other';

      setDraft({
        merchantName: data.merchantName || '',
        amount: data.amount || '',
        transactionDate: data.transactionDate || '',
        transactionTime: data.transactionTime || '',
        paymentMethod: data.paymentMethod || '',
        referenceId: data.referenceId || '',
        invoiceNumber: data.invoiceNumber || '',
        suggestedCategory: category,
        customCategoryDetail: '',
        rawText: data.rawText || payload.rawText || '',
        missingFields: missing,
        duplicateReason: dupReason,
      });
    } catch (err) {
      setScanError(err instanceof Error ? err.message : 'Unable to scan receipt');
    } finally {
      setScanning(false);
    }
  };

  const handleFileSelected = async (file: File) => {
    if (file.type.startsWith('image/')) {
      setPreviewUrl(URL.createObjectURL(file));
    } else {
      setPreviewUrl(null);
    }

    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || '');
      const base64 = result.includes(',') ? result.split(',')[1] : result;
      runOcr({
        imageBase64: base64,
        mimeType: file.type || 'image/jpeg',
      });
    };
    reader.readAsDataURL(file);
  };

  const handleConfirmSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!draft) return;

    const amountNum = Number(draft.amount);
    if (!draft.merchantName.trim()) {
      setSaveError('Please enter the merchant name.');
      return;
    }
    if (!Number.isFinite(amountNum) || amountNum <= 0) {
      setSaveError('Please enter a valid positive transaction amount.');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.transactionDate)) {
      setSaveError('Please specify the actual transaction date (YYYY-MM-DD).');
      return;
    }
    if (!/^\d{2}:\d{2}/.test(draft.transactionTime)) {
      setSaveError('Please specify the transaction time (HH:mm).');
      return;
    }

    const trimmedCustomDetail =
      draft.suggestedCategory === 'Other' ? draft.customCategoryDetail.trim() : '';
    if (draft.suggestedCategory === 'Other' && !trimmedCustomDetail) {
      setSaveError('Please enter where you spent this money for the Other category.');
      return;
    }

    const err = onConfirmSave({
      amount: amountNum,
      description: draft.merchantName.trim(),
      merchant: draft.merchantName.trim(),
      category: draft.suggestedCategory,
      customCategoryDetail: trimmedCustomDetail,
      paymentMethod: draft.paymentMethod.trim() || 'UPI',
      note: draft.invoiceNumber
        ? `Receipt OCR · Invoice ${draft.invoiceNumber}`
        : 'Imported via Receipt OCR',
      transactionDate: draft.transactionDate,
      transactionTime: draft.transactionTime,
      referenceId: draft.referenceId.trim(),
      invoiceNumber: draft.invoiceNumber.trim(),
    });

    if (err) {
      setSaveError(err);
    } else {
      setDraft(null);
      setPreviewUrl(null);
      setRawText('');
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
          IMPORT RECEIPT
        </h1>
        <p className="text-sm text-[#9CA3AF] mt-1 max-w-2xl">
          Capture a printed bill, upload a digital payment screenshot, import a PDF receipt, or paste bill/SMS text. Missing or unclear fields are highlighted for you to verify before saving.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Upload & Input Panel */}
        <div className="lg:col-span-5 bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-5">
          <input
            ref={cameraInputRef}
            type="file"
            accept="image/*"
            capture="environment"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) handleFileSelected(f);
            }}
          />
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) handleFileSelected(f);
            }}
          />
          <input
            ref={pdfInputRef}
            type="file"
            accept="application/pdf"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) handleFileSelected(f);
            }}
          />

          <div className="grid grid-cols-3 gap-3">
            <button
              type="button"
              onClick={() => cameraInputRef.current?.click()}
              className="min-h-[76px] p-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-xs flex flex-col items-center justify-center gap-2 hover:bg-[#d4ff66] cursor-pointer"
            >
              <Camera className="w-5 h-5" />
              <span>Camera</span>
            </button>
            <button
              type="button"
              onClick={() => imageInputRef.current?.click()}
              className="min-h-[76px] p-3 rounded-xl bg-[#181C22] border border-white/10 text-[#F3F5F7] font-medium text-xs flex flex-col items-center justify-center gap-2 hover:bg-[#20252d] cursor-pointer"
            >
              <Upload className="w-5 h-5 text-[#C6FF33]" />
              <span>Gallery Image</span>
            </button>
            <button
              type="button"
              onClick={() => pdfInputRef.current?.click()}
              className="min-h-[76px] p-3 rounded-xl bg-[#181C22] border border-white/10 text-[#F3F5F7] font-medium text-xs flex flex-col items-center justify-center gap-2 hover:bg-[#20252d] cursor-pointer"
            >
              <FileText className="w-5 h-5 text-[#FF6B2C]" />
              <span>PDF Receipt</span>
            </button>
          </div>

          {previewUrl && (
            <div className="rounded-xl overflow-hidden border border-white/10 bg-[#0B0D0F] max-h-56 flex items-center justify-center">
              <img
                src={previewUrl}
                alt="Uploaded receipt preview"
                className="max-h-56 object-contain"
                referrerPolicy="no-referrer"
              />
            </div>
          )}

          <div className="pt-4 border-t border-white/10 space-y-3">
            <label className="block text-xs font-medium text-[#9CA3AF]">
              Or paste digital receipt / UPI confirmation text
            </label>
            <textarea
              rows={4}
              value={rawText}
              onChange={(e) => setRawText(e.target.value)}
              placeholder="Paste receipt text, invoice details, or UPI alert text..."
              className="w-full px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-xs font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
            />
            <button
              type="button"
              disabled={scanning || !rawText.trim()}
              onClick={() => runOcr({ rawText })}
              className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-sm font-medium text-[#C6FF33] hover:bg-[#20252d] disabled:opacity-50 cursor-pointer"
            >
              Extract Fields from Text
            </button>
          </div>

          {scanning && (
            <div className="flex items-center gap-2.5 text-sm text-[#C6FF33]">
              <Loader2 className="w-4 h-4 animate-spin" />
              <span>Scanning receipt fields...</span>
            </div>
          )}

          {scanError && (
            <div className="p-3 rounded-xl bg-[#EF4444]/10 border border-[#EF4444]/30 text-xs text-[#FCA5A5]">
              {scanError}
            </div>
          )}
        </div>

        {/* Confirmation Form */}
        <div className="lg:col-span-7 bg-[#121519] border border-white/10 rounded-2xl p-6">
          {!draft ? (
            <div className="py-16 text-center">
              <p className="text-sm font-medium text-[#F3F5F7]">
                No receipt scanned yet
              </p>
              <p className="text-xs text-[#9CA3AF] mt-1.5 max-w-md mx-auto">
                Upload a receipt photo, PDF invoice, or paste payment confirmation text on the left to inspect and edit every extracted field here.
              </p>
            </div>
          ) : (
            <form onSubmit={handleConfirmSubmit} className="space-y-4">
              <div className="flex items-center justify-between">
                <h2 className="text-base font-semibold text-[#F3F5F7]">
                  Confirm Extracted Receipt Details
                </h2>
                <span className="text-xs text-[#2CE59B] inline-flex items-center gap-1">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  Editable before saving
                </span>
              </div>

              {draft.duplicateReason && (
                <div className="p-3.5 rounded-xl bg-[#FF6B2C]/15 border border-[#FF6B2C]/40 text-xs text-[#FDBA74] flex items-start gap-2.5">
                  <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-[#FF6B2C]" />
                  <span>{draft.duplicateReason}</span>
                </div>
              )}

              {draft.missingFields.length > 0 && (
                <div className="p-3.5 rounded-xl bg-[#C6FF33]/10 border border-[#C6FF33]/30 text-xs text-[#E2FF99]">
                  Missing or unclear in receipt: <strong>{draft.missingFields.join(', ')}</strong>. Please enter these values directly.
                </div>
              )}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Merchant Name *
                  </label>
                  <input
                    type="text"
                    required
                    value={draft.merchantName}
                    onChange={(e) => setDraft({ ...draft, merchantName: e.target.value })}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Amount ({currencySymbol}) *
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0.01"
                    required
                    value={draft.amount}
                    onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Transaction Date *
                  </label>
                  <input
                    type="date"
                    required
                    value={draft.transactionDate}
                    onChange={(e) => setDraft({ ...draft, transactionDate: e.target.value })}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Transaction Time *
                  </label>
                  <input
                    type="time"
                    required
                    value={draft.transactionTime}
                    onChange={(e) => setDraft({ ...draft, transactionTime: e.target.value })}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Payment Method
                  </label>
                  <input
                    type="text"
                    value={draft.paymentMethod}
                    onChange={(e) => setDraft({ ...draft, paymentMethod: e.target.value })}
                    placeholder="UPI, Card, Cash..."
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Category
                  </label>
                  <select
                    value={draft.suggestedCategory}
                    onChange={(e) => {
                      const nextCat = e.target.value as ExpenseCategory;
                      setDraft({
                        ...draft,
                        suggestedCategory: nextCat,
                        customCategoryDetail:
                          nextCat === 'Other' ? draft.customCategoryDetail : '',
                      });
                      if (saveError) setSaveError(null);
                    }}
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  >
                    {EXPENSE_CATEGORIES.map((cat) => (
                      <option key={cat} value={cat}>
                        {cat}
                      </option>
                    ))}
                  </select>
                </div>
                {draft.suggestedCategory === 'Other' && (
                  <div className="sm:col-span-2 rounded-xl bg-[#0B0D0F] border border-[#C6FF33]/45 p-3.5 space-y-2">
                    <label
                      htmlFor="receipt-other-detail"
                      className="block text-xs font-semibold text-[#C6FF33]"
                    >
                      Where have you spent this money? *
                    </label>
                    <input
                      id="receipt-other-detail"
                      type="text"
                      required
                      maxLength={120}
                      value={draft.customCategoryDetail}
                      onChange={(e) => {
                        setDraft({ ...draft, customCategoryDetail: e.target.value });
                        if (saveError) setSaveError(null);
                      }}
                      placeholder="Enter expense details (e.g. Mobile recharge, Movie ticket, Gift...)"
                      className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#121519] border border-[#C6FF33]/35 text-sm text-[#F3F5F7] placeholder:text-[#6B7280] focus:outline-none focus:border-[#C6FF33]"
                    />
                  </div>
                )}
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Transaction Reference ID
                  </label>
                  <input
                    type="text"
                    value={draft.referenceId}
                    onChange={(e) => setDraft({ ...draft, referenceId: e.target.value })}
                    placeholder="UTR / UPI Ref ID"
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Invoice Number
                  </label>
                  <input
                    type="text"
                    value={draft.invoiceNumber}
                    onChange={(e) => setDraft({ ...draft, invoiceNumber: e.target.value })}
                    placeholder="INV-..."
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              </div>

              {saveError && <p className="text-xs text-[#EF4444]">{saveError}</p>}

              <div className="flex items-center gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setDraft(null)}
                  className="min-h-[46px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-sm text-[#9CA3AF] hover:text-white cursor-pointer"
                >
                  Discard
                </button>
                <button
                  type="submit"
                  className="flex-1 min-h-[46px] px-5 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] cursor-pointer"
                >
                  Confirm &amp; Save Transaction
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};
