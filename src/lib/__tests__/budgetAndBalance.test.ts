import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateBalanceSummary,
  getBudgetColorState,
  validateTransactionBalance,
} from '../ledgerEngine';
import { Transaction } from '../../types/expense';

function makeTx(
  id: string,
  type: 'EXPENSE' | 'INCOME',
  amount: number,
  date = '2026-11-15',
  entrySource: Transaction['entrySource'] = 'MANUAL'
): Transaction {
  return {
    id,
    uid: 'u_test',
    type,
    amount,
    description: `Test ${id}`,
    merchant: 'Merchant',
    category: type === 'INCOME' ? 'Income' : 'Food',
    customCategoryDetail: '',
    paymentMethod: 'UPI',
    sourceOfMoney: type === 'INCOME' ? 'Salary' : '',
    note: '',
    transactionDate: date,
    transactionTime: '12:00',
    timestampMillis: new Date(`${date}T12:00:00`).getTime(),
    referenceId: '',
    invoiceNumber: '',
    entrySource,
    createdAtMillis: 1000,
    updatedAtMillis: 1000,
  };
}

test('1. Monthly budget usage below 50% produces GREEN', () => {
  const summary = calculateBalanceSummary(
    20000,
    10000,
    [makeTx('e1', 'EXPENSE', 3500)],
    '2026-11'
  );
  assert.equal(summary.budgetUsagePercent, 35);
  assert.equal(summary.budgetStatusColor, 'GREEN');
  assert.equal(summary.isBudgetExceeded, false);
  assert.equal(getBudgetColorState(4999, 10000), 'GREEN');
});

test('2. Monthly budget usage at exact 50% produces YELLOW', () => {
  const summary = calculateBalanceSummary(
    20000,
    10000,
    [makeTx('e1', 'EXPENSE', 5000)],
    '2026-11'
  );
  assert.equal(summary.budgetUsagePercent, 50);
  assert.equal(summary.budgetStatusColor, 'YELLOW');
  assert.equal(summary.isBudgetExceeded, false);
  assert.equal(getBudgetColorState(5000, 10000), 'YELLOW');
});

test('3. Monthly budget usage above 50% produces RED', () => {
  const at65 = calculateBalanceSummary(
    20000,
    10000,
    [makeTx('e1', 'EXPENSE', 6500)],
    '2026-11'
  );
  assert.equal(at65.budgetUsagePercent, 65);
  assert.equal(at65.budgetStatusColor, 'RED');
  assert.equal(at65.isBudgetExceeded, false);

  const at100 = calculateBalanceSummary(
    20000,
    10000,
    [makeTx('e1', 'EXPENSE', 10000)],
    '2026-11'
  );
  assert.equal(at100.budgetUsagePercent, 100);
  assert.equal(at100.budgetStatusColor, 'RED');
  assert.equal(at100.isBudgetExceeded, true);
});

test('4. Monthly budget usage above 100% keeps uncapped percent and clamps visual fill at 100%', () => {
  const summary = calculateBalanceSummary(
    30000,
    10000,
    [makeTx('e1', 'EXPENSE', 12000)],
    '2026-11'
  );
  assert.equal(summary.budgetUsagePercent, 120);
  assert.equal(summary.remainingBudget, -2000);
  assert.equal(summary.budgetStatusColor, 'RED');
  assert.equal(summary.isBudgetExceeded, true);
  const visualFill = Math.min(Math.max(summary.budgetUsagePercent, 0), 100);
  assert.equal(visualFill, 100);
});

test('5. Zero monthly budget handles calculation safely without division errors', () => {
  const summary = calculateBalanceSummary(
    5000,
    0,
    [makeTx('e1', 'EXPENSE', 1200)],
    '2026-11'
  );
  assert.equal(summary.budgetUsagePercent, 0);
  assert.equal(summary.budgetStatusColor, 'GREEN');
  assert.equal(summary.isBudgetExceeded, false);
  assert.equal(summary.availableBalance, 3800);
});

test('6. Available balance equals 0 displays exhausted state and blocks expense', () => {
  const summary = calculateBalanceSummary(
    500,
    1000,
    [makeTx('e1', 'EXPENSE', 500)],
    '2026-11'
  );
  assert.equal(summary.availableBalance, 0);
  assert.equal(summary.isBalanceExhausted, true);

  const validation = validateTransactionBalance('EXPENSE', 50, summary.availableBalance);
  assert.equal(validation.allowed, false);
  assert.equal(validation.errorTitle, 'Insufficient Balance!');
  assert.equal(validation.currentAvailableBalance, 0);
  assert.equal(validation.shortfallAmount, 50);
});

test('7. Expense exceeding available balance is blocked with shortfall calculation', () => {
  const validation = validateTransactionBalance('EXPENSE', 500, 350);
  assert.equal(validation.allowed, false);
  assert.equal(validation.errorTitle, 'Insufficient Balance!');
  assert.equal(
    validation.errorMessage,
    "You don't have enough money to complete this expense."
  );
  assert.equal(validation.currentAvailableBalance, 350);
  assert.equal(validation.shortfallAmount, 150);
});

test('8. Expense equal to remaining available balance succeeds and exhausts balance to zero', () => {
  const validation = validateTransactionBalance('EXPENSE', 350, 350);
  assert.equal(validation.allowed, true);
  assert.equal(validation.willExhaustBalance, true);
  assert.equal(validation.shortfallAmount, 0);

  const nextSummary = calculateBalanceSummary(
    350,
    1000,
    [makeTx('e1', 'EXPENSE', 350)],
    '2026-11'
  );
  assert.equal(nextSummary.availableBalance, 0);
  assert.equal(nextSummary.isBalanceExhausted, true);
});

test('9. Adding income updates available balance and permits expenses up to new balance', () => {
  const summary = calculateBalanceSummary(
    0,
    5000,
    [makeTx('inc1', 'INCOME', 2500)],
    '2026-11'
  );
  assert.equal(summary.availableBalance, 2500);
  assert.equal(summary.isBalanceExhausted, false);

  const validation = validateTransactionBalance('EXPENSE', 2000, summary.availableBalance);
  assert.equal(validation.allowed, true);
  assert.equal(validation.willExhaustBalance, false);
});

test('10. Editing and deleting transactions recalculate balance and exclude old amount', () => {
  const existingExpense = makeTx('e1', 'EXPENSE', 200);
  const summary = calculateBalanceSummary(
    500,
    1000,
    [existingExpense],
    '2026-11'
  );
  assert.equal(summary.availableBalance, 300);

  // Edit e1 (₹200) -> ₹450: excludes old ₹200 so effective balance is ₹500 -> allowed
  const allowedEdit = validateTransactionBalance(
    'EXPENSE',
    450,
    summary.availableBalance,
    existingExpense
  );
  assert.equal(allowedEdit.allowed, true);
  assert.equal(allowedEdit.currentAvailableBalance, 500);

  // Edit e1 (₹200) -> ₹600: exceeds effective balance ₹500 -> blocked with shortfall ₹100
  const blockedEdit = validateTransactionBalance(
    'EXPENSE',
    600,
    summary.availableBalance,
    existingExpense
  );
  assert.equal(blockedEdit.allowed, false);
  assert.equal(blockedEdit.currentAvailableBalance, 500);
  assert.equal(blockedEdit.shortfallAmount, 100);

  // Delete e1 -> restores available balance ₹500 and usage 0%
  const afterDelete = calculateBalanceSummary(500, 1000, [], '2026-11');
  assert.equal(afterDelete.availableBalance, 500);
  assert.equal(afterDelete.budgetUsagePercent, 0);
  assert.equal(afterDelete.budgetStatusColor, 'GREEN');
});

test('11. Exceeded monthly budget still permits receipt import / expense if available balance is sufficient', () => {
  const summary = calculateBalanceSummary(
    13000,
    10000,
    [makeTx('e1', 'EXPENSE', 11000)],
    '2026-11'
  );
  assert.equal(summary.availableBalance, 2000);
  assert.equal(summary.budgetUsagePercent, 110);
  assert.equal(summary.isBudgetExceeded, true);
  assert.equal(summary.budgetStatusColor, 'RED');

  const receiptValidation = validateTransactionBalance(
    'EXPENSE',
    1500,
    summary.availableBalance
  );
  assert.equal(receiptValidation.allowed, true);
  assert.equal(receiptValidation.willExhaustBalance, false);
});
