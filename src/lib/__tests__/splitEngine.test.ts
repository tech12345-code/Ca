import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildUpiPaymentUri,
  calculateSplitShares,
  categorizeSettlementDue,
  computeGroupBalances,
  createSettlementWithRequest,
  leaveGroupMemberState,
  parseUpiPaymentUri,
  reconcileOfflineExpenseSync,
  respondToInvitationState,
  transitionSettlementStatus,
  validateUpiId,
} from '../splitEngine';
import { Group, GroupExpense, GroupInvitation, GroupMember, Settlement } from '../../types/expense';

function makeMember(
  groupId: string,
  uid: string,
  displayName: string,
  role: 'ADMIN' | 'MEMBER' = 'MEMBER',
  upiId = ''
): GroupMember {
  return {
    id: `${groupId}_${uid}`,
    groupId,
    uid,
    displayName,
    appUserCode: `SEM-${uid.toUpperCase()}`,
    role,
    status: 'ACTIVE',
    upiId,
    upiVerified: Boolean(upiId),
    joinedAtMillis: 1700000000000,
    updatedAtMillis: 1700000000000,
  };
}

function makeExpense(params: {
  id: string;
  groupId: string;
  paidByUid: string;
  paidByName: string;
  amount: number;
  shares: Record<string, number>;
  splitType?: GroupExpense['splitType'];
  deleted?: boolean;
  pendingSync?: boolean;
  syncAction?: GroupExpense['syncAction'];
  updatedAtMillis?: number;
}): GroupExpense {
  const memberUids = Object.keys(params.shares);
  return {
    id: params.id,
    groupId: params.groupId,
    memberUids,
    paidByUid: params.paidByUid,
    paidByName: params.paidByName,
    amount: params.amount,
    description: `Expense ${params.id}`,
    category: 'Food',
    transactionDate: '2026-11-15',
    transactionTime: '12:00',
    timestampMillis: 1700000000000,
    splitType: params.splitType || 'EQUAL',
    participantUids: memberUids,
    shares: params.shares,
    deleted: params.deleted,
    pendingSync: params.pendingSync,
    syncAction: params.syncAction,
    createdAtMillis: 1700000000000,
    updatedAtMillis: params.updatedAtMillis || 1700000000000,
  };
}

test('1. Two members split an expense equally (prompt example: A=200, B=80 -> B owes A 60)', () => {
  const members = [
    makeMember('g1', 'uA', 'Member A', 'ADMIN', 'membera@okaxis'),
    makeMember('g1', 'uB', 'Member B', 'MEMBER', 'memberb@okicici'),
  ];
  const s1 = calculateSplitShares(200, 'EQUAL', ['uA', 'uB']);
  const s2 = calculateSplitShares(80, 'EQUAL', ['uA', 'uB']);
  assert.equal(s1.valid, true);
  assert.deepEqual(s1.shares, { uA: 100, uB: 100 });
  assert.deepEqual(s2.shares, { uA: 40, uB: 40 });

  const expenses = [
    makeExpense({ id: 'e1', groupId: 'g1', paidByUid: 'uA', paidByName: 'Member A', amount: 200, shares: s1.shares }),
    makeExpense({ id: 'e2', groupId: 'g1', paidByUid: 'uB', paidByName: 'Member B', amount: 80, shares: s2.shares }),
  ];

  const summary = computeGroupBalances(members, expenses, []);
  assert.equal(summary.totalGroupExpenses, 280);
  const balA = summary.memberBalances.find((m) => m.uid === 'uA')!;
  const balB = summary.memberBalances.find((m) => m.uid === 'uB')!;
  assert.equal(balA.netBalance, 60);
  assert.equal(balB.netBalance, -60);
  assert.equal(summary.simplifiedDebts.length, 1);
  assert.deepEqual(
    {
      fromUid: summary.simplifiedDebts[0].fromUid,
      toUid: summary.simplifiedDebts[0].toUid,
      amount: summary.simplifiedDebts[0].amount,
    },
    { fromUid: 'uB', toUid: 'uA', amount: 60 }
  );
});

test('2. Three or more members split an expense with exact paisa rounding conservation', () => {
  const members = [
    makeMember('g2', 'u1', 'Alex', 'ADMIN'),
    makeMember('g2', 'u2', 'Priya'),
    makeMember('g2', 'u3', 'Rohan'),
  ];
  const split = calculateSplitShares(100, 'EQUAL', ['u1', 'u2', 'u3']);
  assert.equal(split.valid, true);
  const sum = Object.values(split.shares).reduce((a, b) => a + b, 0);
  assert.equal(Math.round(sum * 100), 10000);
  assert.equal(split.shares.u1, 33.34);
  assert.equal(split.shares.u2, 33.33);
  assert.equal(split.shares.u3, 33.33);

  const expenses = [
    makeExpense({ id: 'e3', groupId: 'g2', paidByUid: 'u1', paidByName: 'Alex', amount: 100, shares: split.shares }),
  ];
  const summary = computeGroupBalances(members, expenses, []);
  assert.equal(summary.simplifiedDebts.length, 2);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'u1')!.netBalance, 66.66);
});

test('3. One member pays the entire expense for 4 roommates', () => {
  const members = [
    makeMember('g3', 'u1', 'Neha', 'ADMIN'),
    makeMember('g3', 'u2', 'Karan'),
    makeMember('g3', 'u3', 'Sam'),
    makeMember('g3', 'u4', 'Tara'),
  ];
  const split = calculateSplitShares(4000, 'EQUAL', ['u1', 'u2', 'u3', 'u4']);
  const expenses = [
    makeExpense({ id: 'rent', groupId: 'g3', paidByUid: 'u1', paidByName: 'Neha', amount: 4000, shares: split.shares }),
  ];
  const summary = computeGroupBalances(members, expenses, []);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'u1')!.netBalance, 3000);
  assert.equal(summary.simplifiedDebts.length, 3);
  for (const debt of summary.simplifiedDebts) {
    assert.equal(debt.toUid, 'u1');
    assert.equal(debt.amount, 1000);
  }
});

test('4. Members make multiple payments across categories + partial settlement', () => {
  const members = [
    makeMember('g4', 'uA', 'A', 'ADMIN'),
    makeMember('g4', 'uB', 'B'),
    makeMember('g4', 'uC', 'C'),
  ];
  const s1 = calculateSplitShares(900, 'EQUAL', ['uA', 'uB', 'uC']).shares; // 300 each
  const s2 = calculateSplitShares(600, 'EQUAL', ['uA', 'uB', 'uC']).shares; // 200 each
  const expenses = [
    makeExpense({ id: 'e1', groupId: 'g4', paidByUid: 'uA', paidByName: 'A', amount: 900, shares: s1 }),
    makeExpense({ id: 'e2', groupId: 'g4', paidByUid: 'uB', paidByName: 'B', amount: 600, shares: s2 }),
  ];
  // Fair share: 500 each. Net before settle: A=+400, B=+100, C=-500.
  // Partial settlement C -> A of 250 completed:
  const completedPartial: Settlement = {
    id: 'st_1',
    groupId: 'g4',
    groupName: 'Trip',
    memberUids: ['uA', 'uB', 'uC'],
    payerUid: 'uC',
    payerName: 'C',
    receiverUid: 'uA',
    receiverName: 'A',
    receiverUpiId: 'a@upi',
    amount: 250,
    dueDate: '2026-11-20',
    reminderEnabled: true,
    status: 'COMPLETED',
    paymentMethod: 'UPI',
    paymentReference: 'UTR1001',
    idempotencyKey: 'k1',
    payerConfirmedAtMillis: 1700001000000,
    receiverConfirmedAtMillis: 1700002000000,
    note: 'Partial',
    createdAtMillis: 1700000000000,
    updatedAtMillis: 1700002000000,
  };
  const summary = computeGroupBalances(members, expenses, [completedPartial]);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uA')!.netBalance, 150);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uB')!.netBalance, 100);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uC')!.netBalance, -250);
});

test('5. Custom amount, percentage, and individual share splits work accurately', () => {
  const custom = calculateSplitShares(500, 'CUSTOM_AMOUNT', ['u1', 'u2', 'u3'], {
    u1: 250,
    u2: 150,
    u3: 100,
  });
  assert.equal(custom.valid, true);
  assert.deepEqual(custom.shares, { u1: 250, u2: 150, u3: 100 });

  const invalidCustom = calculateSplitShares(500, 'CUSTOM_AMOUNT', ['u1', 'u2'], {
    u1: 200,
    u2: 200,
  });
  assert.equal(invalidCustom.valid, false);

  const pct = calculateSplitShares(1000, 'PERCENTAGE', ['u1', 'u2'], { u1: 70, u2: 30 });
  assert.equal(pct.valid, true);
  assert.deepEqual(pct.shares, { u1: 700, u2: 300 });

  const shares = calculateSplitShares(600, 'SHARES', ['u1', 'u2', 'u3'], {
    u1: 2,
    u2: 1,
    u3: 3,
  });
  assert.equal(shares.valid, true);
  assert.deepEqual(shares.shares, { u1: 200, u2: 100, u3: 300 });
});

test('6. Expense edited or deleted recalculates member balances immediately', () => {
  const members = [makeMember('g6', 'u1', 'A', 'ADMIN'), makeMember('g6', 'u2', 'B')];
  const initialExp = makeExpense({
    id: 'e_edit',
    groupId: 'g6',
    paidByUid: 'u1',
    paidByName: 'A',
    amount: 500,
    shares: { u1: 250, u2: 250 },
  });
  let summary = computeGroupBalances(members, [initialExp], []);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'u2')!.netBalance, -250);

  // Edit expense to 300
  const editedExp: GroupExpense = {
    ...initialExp,
    amount: 300,
    shares: { u1: 150, u2: 150 },
  };
  summary = computeGroupBalances(members, [editedExp], []);
  assert.equal(summary.totalGroupExpenses, 300);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'u2')!.netBalance, -150);

  // Soft-delete expense -> balances reset to 0
  const deletedExp: GroupExpense = { ...editedExp, deleted: true };
  summary = computeGroupBalances(members, [deletedExp], []);
  assert.equal(summary.totalGroupExpenses, 0);
  assert.equal(summary.simplifiedDebts.length, 0);
});

test('7. Member leaves group: blocked when unsettled debt exists, allowed once settled & transfers admin cleanly', () => {
  const group: Group = {
    id: 'g7',
    name: 'Flat 4B',
    groupType: 'Roommates',
    iconKey: 'home',
    joinCode: 'GRP-123456',
    creatorUid: 'u1',
    creatorName: 'A',
    memberUids: ['u1', 'u2'],
    settlementDueDate: '',
    createdAtMillis: 1700000000000,
    updatedAtMillis: 1700000000000,
  };
  const members = [makeMember('g7', 'u1', 'A', 'ADMIN'), makeMember('g7', 'u2', 'B', 'MEMBER')];
  const expenses = [
    makeExpense({
      id: 'e7',
      groupId: 'g7',
      paidByUid: 'u1',
      paidByName: 'A',
      amount: 200,
      shares: { u1: 100, u2: 100 },
    }),
  ];

  // Unsettled leave rejected
  const blocked = leaveGroupMemberState(group, members, 'u2', expenses, []);
  assert.equal(blocked.allowed, false);

  // Completed settlement zeros debt -> u1 (Admin) leaves and transfers Admin to u2
  const settled: Settlement = {
    id: 's7',
    groupId: 'g7',
    groupName: 'Flat 4B',
    memberUids: ['u1', 'u2'],
    payerUid: 'u2',
    payerName: 'B',
    receiverUid: 'u1',
    receiverName: 'A',
    receiverUpiId: 'a@upi',
    amount: 100,
    dueDate: '',
    reminderEnabled: false,
    status: 'COMPLETED',
    paymentMethod: 'UPI',
    paymentReference: 'UTR7777',
    idempotencyKey: 'k7',
    payerConfirmedAtMillis: 1700000100000,
    receiverConfirmedAtMillis: 1700000200000,
    note: 'Done',
    createdAtMillis: 1700000000000,
    updatedAtMillis: 1700000200000,
  };
  const allowed = leaveGroupMemberState(group, members, 'u1', expenses, [settled]);
  assert.equal(allowed.allowed, true);
  assert.deepEqual(allowed.updatedGroup!.memberUids, ['u2']);
  assert.equal(allowed.updatedMembers!.find((m) => m.uid === 'u1')!.status, 'LEFT');
  assert.equal(allowed.updatedMembers!.find((m) => m.uid === 'u2')!.role, 'ADMIN');
});

test('8. Invitation rejected: does not grant membership and prevents re-answering settled invitation', () => {
  const invite: GroupInvitation = {
    id: 'inv1',
    groupId: 'g8',
    groupName: 'Goa Trip',
    groupType: 'Trip Group',
    inviterUid: 'u1',
    inviterName: 'A',
    inviteMethod: 'IN_APP',
    targetUid: 'u9',
    targetAppCode: 'SEM-U9000',
    targetEmail: 'u9@example.com',
    targetPhoneMasked: '',
    inviteToken: 'TOK123',
    status: 'PENDING',
    createdAtMillis: 1700000000000,
    updatedAtMillis: 1700000000000,
  };

  const res = respondToInvitationState(invite, 'u9', 'REJECT');
  assert.equal(res.invitation.status, 'REJECTED');
  assert.equal(res.joinedMember, null);

  assert.throws(() => respondToInvitationState(res.invitation, 'u9', 'ACCEPT'));
});

test('9. Payment initiated but fails: balances remain unsettled and status is FAILED', () => {
  const members = [
    makeMember('g9', 'uA', 'A', 'ADMIN', 'a@okaxis'),
    makeMember('g9', 'uB', 'B'),
  ];
  const expenses = [
    makeExpense({
      id: 'e9',
      groupId: 'g9',
      paidByUid: 'uA',
      paidByName: 'A',
      amount: 1000,
      shares: { uA: 500, uB: 500 },
    }),
  ];

  const { settlement } = createSettlementWithRequest({
    id: 's9',
    groupId: 'g9',
    groupName: 'Flat',
    memberUids: ['uA', 'uB'],
    payerUid: 'uB',
    payerName: 'B',
    receiverUid: 'uA',
    receiverName: 'A',
    receiverUpiId: 'a@okaxis',
    amount: 500,
    dueDate: '2026-11-10',
    reminderEnabled: true,
    paymentMethod: 'UPI',
  });

  const initiated = transitionSettlementStatus({
    settlement,
    existingSettlements: [settlement],
    actorUid: 'uB',
    action: 'INITIATE_UPI',
    paymentApp: 'Google Pay',
  });
  assert.equal(initiated.status, 'PAYMENT_INITIATED');

  const failed = transitionSettlementStatus({
    settlement: initiated,
    existingSettlements: [initiated],
    actorUid: 'uB',
    action: 'MARK_FAILED',
    paymentReference: 'BANK_TIMEOUT',
  });
  assert.equal(failed.status, 'FAILED');
  assert.equal(categorizeSettlementDue(failed, '2026-11-15'), 'OVERDUE');

  // Outstanding balance still 500 because status is FAILED, not COMPLETED
  const summary = computeGroupBalances(members, expenses, [failed]);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uB')!.netBalance, -500);
});

test('10. Settlement confirmed end-to-end (UPI URI -> Payer Proof -> Receiver Confirm) zeros balances', () => {
  assert.equal(validateUpiId('alex@okaxis').valid, true);
  assert.equal(validateUpiId('pin1234@upi').valid, false);

  const uri = buildUpiPaymentUri({
    upiId: 'alex@okaxis',
    payeeName: 'Alex',
    amount: 60,
    note: 'Roommates settlement',
    transactionRef: 'SEM998877',
  });
  const parsed = parseUpiPaymentUri(uri);
  assert.equal(parsed.valid, true);
  assert.equal(parsed.upiId, 'alex@okaxis');
  assert.equal(parsed.amount, 60);

  const members = [
    makeMember('g10', 'uA', 'Alex', 'ADMIN', 'alex@okaxis'),
    makeMember('g10', 'uB', 'Sam'),
  ];
  const expenses = [
    makeExpense({
      id: 'e10',
      groupId: 'g10',
      paidByUid: 'uA',
      paidByName: 'Alex',
      amount: 120,
      shares: { uA: 60, uB: 60 },
    }),
  ];

  const { settlement } = createSettlementWithRequest({
    id: 's10',
    groupId: 'g10',
    groupName: 'Roommates',
    memberUids: ['uA', 'uB'],
    payerUid: 'uB',
    payerName: 'Sam',
    receiverUid: 'uA',
    receiverName: 'Alex',
    receiverUpiId: 'alex@okaxis',
    amount: 60,
    dueDate: '2026-11-15',
    reminderEnabled: true,
    paymentMethod: 'QR',
  });

  const s1 = transitionSettlementStatus({
    settlement,
    existingSettlements: [settlement],
    actorUid: 'uB',
    action: 'INITIATE_UPI',
    paymentMethod: 'QR',
    paymentApp: 'PhonePe',
  });
  const s2 = transitionSettlementStatus({
    settlement: s1,
    existingSettlements: [s1],
    actorUid: 'uB',
    action: 'SUBMIT_PAYER_PROOF',
    paymentReference: 'UTR44556677',
  });
  assert.equal(s2.status, 'AWAITING_CONFIRMATION');

  // Payer cannot self-confirm receipt
  assert.throws(() =>
    transitionSettlementStatus({
      settlement: s2,
      existingSettlements: [s2],
      actorUid: 'uB',
      action: 'RECEIVER_CONFIRM',
    })
  );

  const s3 = transitionSettlementStatus({
    settlement: s2,
    existingSettlements: [s2],
    actorUid: 'uA',
    action: 'RECEIVER_CONFIRM',
  });
  assert.equal(s3.status, 'COMPLETED');
  assert.equal(categorizeSettlementDue(s3, '2026-11-20'), 'COMPLETED');

  const summary = computeGroupBalances(members, expenses, [s3]);
  assert.equal(summary.simplifiedDebts.length, 0);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uA')!.netBalance, 0);
  assert.equal(summary.memberBalances.find((m) => m.uid === 'uB')!.netBalance, 0);
});

test('11. Duplicate payment confirmation and duplicate UTR reference are strictly prevented', () => {
  const completed: Settlement = {
    id: 's_done',
    groupId: 'g11',
    groupName: 'Group',
    memberUids: ['uA', 'uB'],
    payerUid: 'uB',
    payerName: 'B',
    receiverUid: 'uA',
    receiverName: 'A',
    receiverUpiId: 'a@upi',
    amount: 200,
    dueDate: '2026-11-15',
    reminderEnabled: false,
    status: 'COMPLETED',
    paymentMethod: 'UPI',
    paymentReference: 'UTR99998888',
    idempotencyKey: 'k_done',
    payerConfirmedAtMillis: 1700000100000,
    receiverConfirmedAtMillis: 1700000200000,
    note: 'Done',
    createdAtMillis: 1700000000000,
    updatedAtMillis: 1700000200000,
  };

  // Re-confirming an already completed settlement is blocked
  assert.throws(
    () =>
      transitionSettlementStatus({
        settlement: completed,
        existingSettlements: [completed],
        actorUid: 'uA',
        action: 'RECEIVER_CONFIRM',
      }),
    /Duplicate settlement blocked/
  );

  // Reusing the same UTR on another settlement is blocked
  const second = createSettlementWithRequest({
    id: 's_dup_ref',
    groupId: 'g11',
    groupName: 'Group',
    memberUids: ['uA', 'uB'],
    payerUid: 'uB',
    payerName: 'B',
    receiverUid: 'uA',
    receiverName: 'A',
    receiverUpiId: 'a@upi',
    amount: 200,
    dueDate: '2026-11-15',
    reminderEnabled: false,
    paymentMethod: 'UPI',
  }).settlement;

  assert.throws(
    () =>
      transitionSettlementStatus({
        settlement: second,
        existingSettlements: [completed, second],
        actorUid: 'uB',
        action: 'SUBMIT_PAYER_PROOF',
        paymentReference: 'UTR99998888',
      }),
    /Duplicate payment reference/
  );
});

test('12. Offline group expense entry and later synchronization reconciliation', () => {
  const cloudExpenses: GroupExpense[] = [
    makeExpense({
      id: 'exp_cloud_1',
      groupId: 'g12',
      paidByUid: 'uA',
      paidByName: 'A',
      amount: 100,
      shares: { uA: 50, uB: 50 },
      updatedAtMillis: 1700000100000,
    }),
    makeExpense({
      id: 'exp_to_delete',
      groupId: 'g12',
      paidByUid: 'uA',
      paidByName: 'A',
      amount: 200,
      shares: { uA: 100, uB: 100 },
      updatedAtMillis: 1700000100000,
    }),
  ];

  const localExpenses: GroupExpense[] = [
    // Offline edit of cloud_1 newer than cloud version
    makeExpense({
      id: 'exp_cloud_1',
      groupId: 'g12',
      paidByUid: 'uA',
      paidByName: 'A',
      amount: 160,
      shares: { uA: 80, uB: 80 },
      pendingSync: true,
      syncAction: 'UPDATE',
      updatedAtMillis: 1700000500000,
    }),
    // Offline delete of exp_to_delete
    makeExpense({
      id: 'exp_to_delete',
      groupId: 'g12',
      paidByUid: 'uA',
      paidByName: 'A',
      amount: 200,
      shares: { uA: 100, uB: 100 },
      deleted: true,
      pendingSync: true,
      syncAction: 'DELETE',
      updatedAtMillis: 1700000500000,
    }),
    // Brand new offline expense
    makeExpense({
      id: 'exp_offline_new',
      groupId: 'g12',
      paidByUid: 'uB',
      paidByName: 'B',
      amount: 80,
      shares: { uA: 40, uB: 40 },
      pendingSync: true,
      syncAction: 'CREATE',
      updatedAtMillis: 1700000600000,
    }),
  ];

  const reconciled = reconcileOfflineExpenseSync(localExpenses, cloudExpenses);
  assert.deepEqual(reconciled.pendingToDeleteIds, ['exp_to_delete']);
  assert.equal(reconciled.pendingToPush.length, 2);
  assert.equal(reconciled.mergedExpenses.length, 2);
  assert.equal(
    reconciled.mergedExpenses.find((e) => e.id === 'exp_cloud_1')!.amount,
    160
  );
  assert.ok(reconciled.mergedExpenses.some((e) => e.id === 'exp_offline_new'));
  assert.ok(!reconciled.mergedExpenses.some((e) => e.id === 'exp_to_delete'));
});
