import { jsPDF } from 'jspdf';
import { LedgerEntry } from '../types/expense';
import { formatCategoryLabel } from './ledgerEngine';

export interface PdfStatementOptions {
  appName: string;
  userName: string;
  userEmail?: string;
  currencySymbol: string;
  startDate: string;
  endDate: string;
  openingBalanceForRange: number;
  totalIncomeForRange: number;
  totalExpensesForRange: number;
  closingBalanceForRange: number;
  entries: LedgerEntry[];
}

export function generatePdfStatement(opts: PdfStatementOptions): void {
  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const margin = 40;
  const currency = opts.currencySymbol === '₹' ? 'INR ' : `${opts.currencySymbol} `;

  const fmt = (val: number) =>
    `${currency}${val.toLocaleString('en-IN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;

  // Header
  doc.setFillColor(11, 13, 15);
  doc.rect(0, 0, pageWidth, 94, 'F');
  doc.setTextColor(198, 255, 51);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(18);
  doc.text(opts.appName, margin, 38);

  doc.setTextColor(243, 245, 247);
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(10);
  doc.text(`Account Holder: ${opts.userName}${opts.userEmail ? ` (${opts.userEmail})` : ''}`, margin, 58);
  doc.text(
    `Statement Period: ${opts.startDate} to ${opts.endDate}   |   Generated: ${new Date().toLocaleString()}`,
    margin,
    74
  );

  // Summary Block
  let y = 118;
  doc.setDrawColor(220, 224, 230);
  doc.setFillColor(248, 250, 252);
  doc.roundedRect(margin, y, pageWidth - margin * 2, 74, 6, 6, 'FD');

  doc.setTextColor(15, 23, 42);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(10);
  doc.text(`Opening Balance: ${fmt(opts.openingBalanceForRange)}`, margin + 16, y + 26);
  doc.text(`Total Income: +${fmt(opts.totalIncomeForRange)}`, pageWidth / 2 + 10, y + 26);
  doc.text(`Total Expenses: -${fmt(opts.totalExpensesForRange)}`, margin + 16, y + 50);
  doc.text(`Closing Balance: ${fmt(opts.closingBalanceForRange)}`, pageWidth / 2 + 10, y + 50);

  y += 102;

  const drawTableHeader = (topY: number) => {
    doc.setFillColor(241, 245, 249);
    doc.rect(margin, topY - 14, pageWidth - margin * 2, 22, 'F');
    doc.setTextColor(51, 65, 85);
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(9);
    doc.text('Date & Time', margin + 6, topY);
    doc.text('Description', margin + 96, topY);
    doc.text('Category', margin + 225, topY);
    doc.text('Amount', margin + 355, topY);
    doc.text('Running Bal', margin + 440, topY);
    return topY + 20;
  };

  y = drawTableHeader(y);

  if (opts.entries.length === 0) {
    doc.setFont('helvetica', 'normal');
    doc.setTextColor(100, 116, 139);
    doc.text('No transactions found within the selected date range.', margin + 6, y + 10);
  } else {
    for (const entry of opts.entries) {
      if (y > pageHeight - 54) {
        doc.addPage();
        y = drawTableHeader(52);
      }
      const tx = entry.transaction;
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(8.8);
      doc.setTextColor(30, 41, 59);

      doc.text(`${tx.transactionDate} ${tx.transactionTime}`, margin + 6, y);
      doc.text(tx.description.slice(0, 23), margin + 96, y);
      doc.text(formatCategoryLabel(tx.category, tx.customCategoryDetail).slice(0, 25), margin + 225, y);

      if (tx.type === 'EXPENSE') {
        doc.setTextColor(194, 65, 12);
        doc.text(`-${fmt(tx.amount)}`, margin + 355, y);
      } else {
        doc.setTextColor(4, 120, 87);
        doc.text(`+${fmt(tx.amount)}`, margin + 355, y);
      }

      doc.setTextColor(15, 23, 42);
      doc.text(fmt(entry.runningBalanceAfter), margin + 440, y);

      doc.setDrawColor(235, 238, 242);
      doc.line(margin, y + 6, pageWidth - margin, y + 6);
      y += 20;
    }
  }

  doc.save(`SmartExpense_Statement_${opts.startDate}_to_${opts.endDate}.pdf`);
}
