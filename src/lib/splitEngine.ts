import {
  Group,
  GroupBalanceSummary,
  GroupExpense,
  GroupInvitation,
  GroupMember,
  GroupType,
  InvitationStatus,
  MemberBalance,
  PaymentRequest,
  Settlement,
  SettlementDueCategory,
  SettlementPaymentMethod,
  SettlementStatus,
  SimplifiedDebt,
  SplitType,
} from '../types/expense';

export function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

export function generateAppUserCode(uid: string): string {
  let hash = 2166136261;
  for (let i = 0; i < uid.length; i += 1) {
    hash ^= uid.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  const suffix = (hash >>> 0).toString(36).toUpperCase().padStart(7, '0').slice(0, 7);
  const head = uid.replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, 2).padEnd(2, 'X');
  return `SEM-${head}${suffix}`;
}

export function generateGroupJoinCode(groupId: string): string {
  let hash = 5381;
  for (let i = 0; i < groupId.length; i += 1) {
    hash = (hash * 33) ^ groupId.charCodeAt(i);
  }
  return `GRP-${(hash >>> 0).toString(36).toUpperCase().padStart(6, '0').slice(0, 6)}`;
}

const UPI_VPA_REGEX = /^[a-zA-Z0-9._-]{2,64}@[a-zA-Z0-9.-]{2,32}$/;

export function validateUpiId(raw: string): { valid: boolean; normalized: string; error?: string } {
  const normalized = raw.trim().toLowerCase();
  if (!normalized) {
    return { valid: false, normalized, error: 'Enter a UPI ID such as username@okaxis.' };
  }
  if (/pin|otp|password|cvv/i.test(normalized)) {
    return {
      valid: false,
      normalized,
      error: 'Never enter UPI PIN, OTP, or banking passwords.',
    };
  }
  if (!UPI_VPA_REGEX.test(normalized)) {
    return {
      valid: false,
      normalized,
      error: 'Invalid UPI ID format. Expected format: handle@bank (e.g., alex@okicici).',
    };
  }
  return { valid: true, normalized };
}

export function maskPhoneNumber(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  if (digits.length < 4) return '****';
  return `••••••${digits.slice(-4)}`;
}

/**
 * Computes exact per-member shares rounded to 2 decimal places so that the
 * sum of shares equals totalAmount to the exact paisa/cent.
 */
export function calculateSplitShares(
  totalAmount: number,
  splitType: SplitType,
  participantUids: string[],
  customInputs: Record<string, number> = {}
): { valid: boolean; shares: Record<string, number>; error?: string } {
  if (!Number.isFinite(totalAmount) || totalAmount <= 0) {
    return { valid: false, shares: {}, error: 'Expense amount must be greater than zero.' };
  }
  const uniqueUids = Array.from(new Set(participantUids.filter(Boolean)));
  if (uniqueUids.length === 0) {
    return { valid: false, shares: {}, error: 'Select at least one member to split with.' };
  }

  const totalCents = Math.round(totalAmount * 100);
  const shares: Record<string, number> = {};

  if (splitType === 'EQUAL') {
    const baseCents = Math.floor(totalCents / uniqueUids.length);
    let remainder = totalCents - baseCents * uniqueUids.length;
    for (const uid of uniqueUids) {
      const extra = remainder > 0 ? 1 : 0;
      if (remainder > 0) remainder -= 1;
      shares[uid] = (baseCents + extra) / 100;
    }
    return { valid: true, shares };
  }

  if (splitType === 'CUSTOM_AMOUNT') {
    let sumCents = 0;
    for (const uid of uniqueUids) {
      const val = Number(customInputs[uid] ?? 0);
      if (!Number.isFinite(val) || val < 0) {
        return { valid: false, shares: {}, error: 'Custom split amounts cannot be negative.' };
      }
      const cents = Math.round(val * 100);
      shares[uid] = cents / 100;
      sumCents += cents;
    }
    if (Math.abs(sumCents - totalCents) > 1) {
      return {
        valid: false,
        shares: {},
        error: `Custom split amounts (${(sumCents / 100).toFixed(2)}) must equal total amount (${(totalCents / 100).toFixed(2)}).`,
      };
    }
    if (sumCents !== totalCents && uniqueUids.length > 0) {
      const lastUid = uniqueUids[uniqueUids.length - 1];
      shares[lastUid] = round2(shares[lastUid] + (totalCents - sumCents) / 100);
    }
    return { valid: true, shares };
  }

  if (splitType === 'PERCENTAGE') {
    let pctSum = 0;
    for (const uid of uniqueUids) {
      const pct = Number(customInputs[uid] ?? 0);
      if (!Number.isFinite(pct) || pct < 0 || pct > 100) {
        return { valid: false, shares: {}, error: 'Percentages must be between 0 and 100.' };
      }
      pctSum += pct;
    }
    if (Math.abs(pctSum - 100) > 0.05) {
      return {
        valid: false,
        shares: {},
        error: `Percentages must add up to 100% (currently ${pctSum.toFixed(1)}%).`,
      };
    }
    let allocatedCents = 0;
    uniqueUids.forEach((uid, idx) => {
      if (idx === uniqueUids.length - 1) {
        shares[uid] = (totalCents - allocatedCents) / 100;
      } else {
        const pct = Number(customInputs[uid] ?? 0);
        const cents = Math.round((totalCents * pct) / 100);
        allocatedCents += cents;
        shares[uid] = cents / 100;
      }
    });
    return { valid: true, shares };
  }

  // SHARES (weight-based)
  let totalWeight = 0;
  for (const uid of uniqueUids) {
    const w = Number(customInputs[uid] ?? 0);
    if (!Number.isFinite(w) || w < 0) {
      return { valid: false, shares: {}, error: 'Individual share counts cannot be negative.' };
    }
    totalWeight += w;
  }
  if (totalWeight <= 0) {
    return { valid: false, shares: {}, error: 'Total shares must be greater than zero.' };
  }
  let allocatedCents = 0;
  uniqueUids.forEach((uid, idx) => {
    if (idx === uniqueUids.length - 1) {
      shares[uid] = (totalCents - allocatedCents) / 100;
    } else {
      const w = Number(customInputs[uid] ?? 0);
      const cents = Math.round((totalCents * w) / totalWeight);
      allocatedCents += cents;
      shares[uid] = cents / 100;
    }
  });
  return { valid: true, shares };
}

/**
 * Core Group Balance & Debt Simplification Engine:
 * Net Balance = (Total Paid - Total Fair Share) + Settled Outgoing - Settled Incoming
 * Only non-deleted expenses and COMPLETED settlements impact final net balances.
 */
export function computeGroupBalances(
  members: GroupMember[],
  expenses: GroupExpense[],
  settlements: Settlement[]
): GroupBalanceSummary {
  const memberMap = new Map<string, GroupMember>();
  for (const m of members) {
    memberMap.set(m.uid, m);
  }

  const paidByUid: Record<string, number> = {};
  const shareByUid: Record<string, number> = {};
  const settledPaidByUid: Record<string, number> = {};
  const settledReceivedByUid: Record<string, number> = {};

  const ensureUid = (uid: string) => {
    if (!(uid in paidByUid)) paidByUid[uid] = 0;
    if (!(uid in shareByUid)) shareByUid[uid] = 0;
    if (!(uid in settledPaidByUid)) settledPaidByUid[uid] = 0;
    if (!(uid in settledReceivedByUid)) settledReceivedByUid[uid] = 0;
  };

  for (const m of members) {
    ensureUid(m.uid);
  }

  let totalGroupExpenses = 0;
  for (const exp of expenses) {
    if (exp.deleted) continue;
    totalGroupExpenses += Number(exp.amount) || 0;
    ensureUid(exp.paidByUid);
    paidByUid[exp.paidByUid] = round2(paidByUid[exp.paidByUid] + Number(exp.amount));

    for (const [uid, shareAmt] of Object.entries(exp.shares || {})) {
      ensureUid(uid);
      shareByUid[uid] = round2(shareByUid[uid] + Number(shareAmt || 0));
    }
  }

  for (const s of settlements) {
    if (s.status !== 'COMPLETED') continue;
    ensureUid(s.payerUid);
    ensureUid(s.receiverUid);
    settledPaidByUid[s.payerUid] = round2(settledPaidByUid[s.payerUid] + Number(s.amount));
    settledReceivedByUid[s.receiverUid] = round2(
      settledReceivedByUid[s.receiverUid] + Number(s.amount)
    );
  }

  const allUids = Array.from(
    new Set([...members.map((m) => m.uid), ...Object.keys(paidByUid), ...Object.keys(shareByUid)])
  );

  const memberBalances: MemberBalance[] = allUids.map((uid) => {
    const meta = memberMap.get(uid);
    const totalPaid = round2(paidByUid[uid] || 0);
    const totalFairShare = round2(shareByUid[uid] || 0);
    const settledPaid = round2(settledPaidByUid[uid] || 0);
    const settledReceived = round2(settledReceivedByUid[uid] || 0);
    const netBalance = round2(totalPaid - totalFairShare + settledPaid - settledReceived);

    return {
      uid,
      displayName: meta?.displayName || uid,
      appUserCode: meta?.appUserCode || generateAppUserCode(uid),
      upiId: meta?.upiId || '',
      upiVerified: Boolean(meta?.upiVerified),
      role: meta?.role || 'MEMBER',
      status: meta?.status || 'ACTIVE',
      totalPaid,
      totalFairShare,
      settledPaid,
      settledReceived,
      netBalance,
    };
  });

  // Greedy minimum-cash-flow simplification between debtors (net < 0) and creditors (net > 0)
  const debtors = memberBalances
    .filter((m) => m.netBalance < -0.005)
    .map((m) => ({ ...m, remaining: round2(-m.netBalance) }))
    .sort((a, b) => b.remaining - a.remaining);

  const creditors = memberBalances
    .filter((m) => m.netBalance > 0.005)
    .map((m) => ({ ...m, remaining: round2(m.netBalance) }))
    .sort((a, b) => b.remaining - a.remaining);

  const simplifiedDebts: SimplifiedDebt[] = [];
  let i = 0;
  let j = 0;
  while (i < debtors.length && j < creditors.length) {
    const debtor = debtors[i];
    const creditor = creditors[j];
    const amt = round2(Math.min(debtor.remaining, creditor.remaining));
    if (amt > 0.005) {
      simplifiedDebts.push({
        fromUid: debtor.uid,
        fromName: debtor.displayName,
        toUid: creditor.uid,
        toName: creditor.displayName,
        toUpiId: creditor.upiId,
        toUpiVerified: creditor.upiVerified,
        amount: amt,
      });
    }
    debtor.remaining = round2(debtor.remaining - amt);
    creditor.remaining = round2(creditor.remaining - amt);
    if (debtor.remaining <= 0.005) i += 1;
    if (creditor.remaining <= 0.005) j += 1;
  }

  return {
    totalGroupExpenses: round2(totalGroupExpenses),
    memberBalances,
    simplifiedDebts,
  };
}

export function computeUserAggregateSplitStats(
  uid: string,
  groups: Group[],
  membersByGroup: Record<string, GroupMember[]>,
  expenses: GroupExpense[],
  settlements: Settlement[]
): {
  totalGroupExpenses: number;
  myOutstandingOwed: number;
  othersOweMe: number;
} {
  let totalGroupExpenses = 0;
  let myOutstandingOwed = 0;
  let othersOweMe = 0;

  for (const g of groups) {
    const gMembers = membersByGroup[g.id] || [];
    const gExpenses = expenses.filter((e) => e.groupId === g.id && !e.deleted);
    const gSettlements = settlements.filter((s) => s.groupId === g.id);
    const summary = computeGroupBalances(gMembers, gExpenses, gSettlements);
    totalGroupExpenses += summary.totalGroupExpenses;
    const mine = summary.memberBalances.find((m) => m.uid === uid);
    if (mine) {
      if (mine.netBalance < -0.005) {
        myOutstandingOwed += -mine.netBalance;
      } else if (mine.netBalance > 0.005) {
        othersOweMe += mine.netBalance;
      }
    }
  }

  return {
    totalGroupExpenses: round2(totalGroupExpenses),
    myOutstandingOwed: round2(myOutstandingOwed),
    othersOweMe: round2(othersOweMe),
  };
}

export function categorizeSettlementDue(
  settlement: Settlement,
  todayDateStr: string = new Date().toISOString().slice(0, 10)
): SettlementDueCategory {
  if (settlement.status === 'COMPLETED') return 'COMPLETED';
  if (!settlement.dueDate) return 'UPCOMING';
  if (settlement.dueDate < todayDateStr) return 'OVERDUE';
  if (settlement.dueDate === todayDateStr) return 'DUE';
  return 'UPCOMING';
}

export function buildUpiPaymentUri(params: {
  upiId: string;
  payeeName: string;
  amount: number;
  note: string;
  transactionRef: string;
}): string {
  const cleanVpa = params.upiId.trim().toLowerCase();
  const amt = round2(params.amount).toFixed(2);
  const query = new URLSearchParams({
    pa: cleanVpa,
    pn: params.payeeName.trim().slice(0, 60) || 'Group Member',
    am: amt,
    cu: 'INR',
    tn: params.note.trim().slice(0, 80) || 'Smart Expense Manager Settlement',
    tr: params.transactionRef.trim().slice(0, 35),
  });
  return `upi://pay?${query.toString()}`;
}

export interface ParsedUpiPayload {
  valid: boolean;
  upiId: string;
  payeeName: string;
  amount: number;
  currency: string;
  note: string;
  transactionRef: string;
  error?: string;
}

export function parseUpiPaymentUri(rawUri: string): ParsedUpiPayload {
  const trimmed = rawUri.trim();
  if (!trimmed.toLowerCase().startsWith('upi://pay')) {
    return {
      valid: false,
      upiId: '',
      payeeName: '',
      amount: 0,
      currency: 'INR',
      note: '',
      transactionRef: '',
      error: 'Scanned QR code is not a valid UPI payment link (expected upi://pay?... ).',
    };
  }
  const queryIndex = trimmed.indexOf('?');
  const search = queryIndex >= 0 ? trimmed.slice(queryIndex + 1) : '';
  const params = new URLSearchParams(search);
  const upiId = (params.get('pa') || '').trim().toLowerCase();
  const payeeName = (params.get('pn') || '').trim();
  const amountRaw = Number(params.get('am') || '0');
  const currency = (params.get('cu') || 'INR').trim().toUpperCase();
  const note = (params.get('tn') || '').trim();
  const transactionRef = (params.get('tr') || '').trim();

  const vpaCheck = validateUpiId(upiId);
  if (!vpaCheck.valid) {
    return {
      valid: false,
      upiId,
      payeeName,
      amount: 0,
      currency,
      note,
      transactionRef,
      error: vpaCheck.error || 'UPI ID missing in scanned QR code.',
    };
  }

  return {
    valid: true,
    upiId: vpaCheck.normalized,
    payeeName,
    amount: Number.isFinite(amountRaw) && amountRaw > 0 ? round2(amountRaw) : 0,
    currency,
    note,
    transactionRef,
  };
}

export function buildGroupInvitationShareText(params: {
  inviterName: string;
  groupName: string;
  groupType: GroupType;
  joinCode: string;
  inviteToken: string;
  appUrl?: string;
}): string {
  const url = params.appUrl || 'https://smart-expense-manager.app';
  return (
    `${params.inviterName} invited you to join "${params.groupName}" (${params.groupType}) on Smart Expense Manager.\n` +
    `Group Join Code: ${params.joinCode}\n` +
    `Invite Token: ${params.inviteToken}\n` +
    `Open the app -> Split Expenses -> Join Group and enter the code above: ${url}`
  );
}

/**
 * Pure state-machine & duplicate-guard functions for settlements and invitations.
 * Reused directly by storageSync, UI components, and automated unit tests.
 */
export function respondToInvitationState(
  invitation: GroupInvitation,
  responderUid: string,
  decision: 'ACCEPT' | 'REJECT',
  nowMillis: number = Date.now()
): {
  invitation: GroupInvitation;
  joinedMember: GroupMember | null;
} {
  if (invitation.status !== 'PENDING') {
    throw new Error(`Invitation is already ${invitation.status} and cannot be modified.`);
  }
  const nextStatus: InvitationStatus = decision === 'ACCEPT' ? 'ACCEPTED' : 'REJECTED';
  const updatedInvitation: GroupInvitation = {
    ...invitation,
    targetUid: responderUid,
    status: nextStatus,
    updatedAtMillis: nowMillis,
  };

  if (decision === 'REJECT') {
    return { invitation: updatedInvitation, joinedMember: null };
  }

  const joinedMember: GroupMember = {
    id: `${invitation.groupId}_${responderUid}`,
    groupId: invitation.groupId,
    uid: responderUid,
    displayName: invitation.targetEmail
      ? invitation.targetEmail.split('@')[0]
      : responderUid,
    appUserCode: generateAppUserCode(responderUid),
    role: 'MEMBER',
    status: 'ACTIVE',
    upiId: '',
    upiVerified: false,
    joinedAtMillis: nowMillis,
    updatedAtMillis: nowMillis,
  };

  return { invitation: updatedInvitation, joinedMember };
}

export function leaveGroupMemberState(
  group: Group,
  members: GroupMember[],
  memberUid: string,
  expenses: GroupExpense[],
  settlements: Settlement[],
  nowMillis: number = Date.now()
): {
  allowed: boolean;
  reason?: string;
  updatedGroup?: Group;
  updatedMembers?: GroupMember[];
} {
  const member = members.find((m) => m.uid === memberUid && m.status === 'ACTIVE');
  if (!member) {
    return { allowed: false, reason: 'User is not an active member of this group.' };
  }

  const summary = computeGroupBalances(members, expenses, settlements);
  const myBalance = summary.memberBalances.find((m) => m.uid === memberUid);
  if (myBalance && Math.abs(myBalance.netBalance) > 0.01) {
    return {
      allowed: false,
      reason: `Clear your outstanding balance (${myBalance.netBalance.toFixed(2)}) before leaving this group.`,
    };
  }

  const activeOthers = members.filter((m) => m.status === 'ACTIVE' && m.uid !== memberUid);
  if (member.role === 'ADMIN' && activeOthers.length > 0) {
    // Transfer admin automatically to earliest remaining active member
    const successorUid = activeOthers[0].uid;
    const updatedMembers = members.map((m) => {
      if (m.uid === memberUid) {
        return { ...m, status: 'LEFT' as const, updatedAtMillis: nowMillis };
      }
      if (m.uid === successorUid) {
        return { ...m, role: 'ADMIN' as const, updatedAtMillis: nowMillis };
      }
      return m;
    });
    const updatedGroup: Group = {
      ...group,
      memberUids: group.memberUids.filter((u) => u !== memberUid),
      updatedAtMillis: nowMillis,
    };
    return { allowed: true, updatedGroup, updatedMembers };
  }

  const updatedMembers = members.map((m) =>
    m.uid === memberUid ? { ...m, status: 'LEFT' as const, updatedAtMillis: nowMillis } : m
  );
  const updatedGroup: Group = {
    ...group,
    memberUids: group.memberUids.filter((u) => u !== memberUid),
    updatedAtMillis: nowMillis,
  };
  return { allowed: true, updatedGroup, updatedMembers };
}

export function createSettlementWithRequest(params: {
  id: string;
  groupId: string;
  groupName: string;
  memberUids: string[];
  payerUid: string;
  payerName: string;
  receiverUid: string;
  receiverName: string;
  receiverUpiId: string;
  amount: number;
  dueDate: string;
  reminderEnabled: boolean;
  paymentMethod: SettlementPaymentMethod;
  note?: string;
  nowMillis?: number;
}): { settlement: Settlement; paymentRequest: PaymentRequest } {
  const now = params.nowMillis ?? Date.now();
  const amt = round2(params.amount);
  if (!Number.isFinite(amt) || amt <= 0) {
    throw new Error('Settlement amount must be greater than zero.');
  }
  const cleanNote = (params.note || `Settlement for ${params.groupName}`).slice(0, 200);
  const trRef = `SEM${now.toString().slice(-8)}${params.id.replace(/[^a-zA-Z0-9]/g, '').slice(-4).toUpperCase()}`;
  const upiUri = params.receiverUpiId
    ? buildUpiPaymentUri({
        upiId: params.receiverUpiId,
        payeeName: params.receiverName,
        amount: amt,
        note: cleanNote,
        transactionRef: trRef,
      })
    : '';

  const settlement: Settlement = {
    id: params.id,
    groupId: params.groupId,
    groupName: params.groupName,
    memberUids: params.memberUids,
    payerUid: params.payerUid,
    payerName: params.payerName,
    receiverUid: params.receiverUid,
    receiverName: params.receiverName,
    receiverUpiId: params.receiverUpiId,
    amount: amt,
    dueDate: params.dueDate,
    reminderEnabled: params.reminderEnabled,
    status: 'PENDING',
    paymentMethod: params.paymentMethod,
    paymentApp: '',
    paymentReference: '',
    idempotencyKey: `idem_${params.id}_${now}`,
    payerConfirmedAtMillis: null,
    receiverConfirmedAtMillis: null,
    note: cleanNote,
    createdAtMillis: now,
    updatedAtMillis: now,
  };

  const paymentRequest: PaymentRequest = {
    id: `req_${params.id}`,
    groupId: params.groupId,
    settlementId: params.id,
    memberUids: params.memberUids,
    requesterUid: params.receiverUid,
    requesterName: params.receiverName,
    requesterUpiId: params.receiverUpiId,
    payerUid: params.payerUid,
    payerName: params.payerName,
    amount: amt,
    description: cleanNote,
    upiUri,
    transactionRef: trRef,
    dueDate: params.dueDate,
    status: 'PENDING',
    createdAtMillis: now,
    updatedAtMillis: now,
  };

  return { settlement, paymentRequest };
}

/**
 * Enforces strict two-stage settlement verification & duplicate payment guard:
 * - Opening UPI app moves PENDING/FAILED -> PAYMENT_INITIATED (never COMPLETED!)
 * - Payer submitting UTR/ref moves PAYMENT_INITIATED -> AWAITING_CONFIRMATION
 * - Receiver confirming receipt moves AWAITING_CONFIRMATION -> COMPLETED
 * - Rejecting/failing moves to REJECTED or FAILED without altering balances
 * - Blocks confirming an already COMPLETED settlement or reusing an already-completed paymentReference
 */
export function transitionSettlementStatus(params: {
  settlement: Settlement;
  existingSettlements: Settlement[];
  actorUid: string;
  action:
    | 'INITIATE_UPI'
    | 'SUBMIT_PAYER_PROOF'
    | 'RECEIVER_CONFIRM'
    | 'REJECT'
    | 'MARK_FAILED';
  paymentApp?: string;
  paymentReference?: string;
  paymentMethod?: SettlementPaymentMethod;
  nowMillis?: number;
}): Settlement {
  const { settlement, existingSettlements, actorUid, action } = params;
  const now = params.nowMillis ?? Date.now();

  if (settlement.status === 'COMPLETED') {
    throw new Error('Duplicate settlement blocked: this settlement is already COMPLETED.');
  }

  if (action === 'INITIATE_UPI') {
    if (actorUid !== settlement.payerUid) {
      throw new Error('Only the payer can initiate this UPI settlement.');
    }
    if (!['PENDING', 'FAILED', 'REJECTED'].includes(settlement.status)) {
      throw new Error(`Cannot initiate payment from status ${settlement.status}.`);
    }
    return {
      ...settlement,
      status: 'PAYMENT_INITIATED',
      paymentMethod: params.paymentMethod || settlement.paymentMethod,
      paymentApp: params.paymentApp || settlement.paymentApp || 'UPI',
      updatedAtMillis: now,
    };
  }

  if (action === 'SUBMIT_PAYER_PROOF') {
    if (actorUid !== settlement.payerUid) {
      throw new Error('Only the payer can submit payment confirmation reference.');
    }
    if (!['PENDING', 'PAYMENT_INITIATED', 'FAILED'].includes(settlement.status)) {
      throw new Error(`Cannot submit payer proof from status ${settlement.status}.`);
    }
    const cleanRef = (params.paymentReference || '').trim();
    if (cleanRef.length < 4) {
      throw new Error('Enter a valid UPI reference / UTR number (at least 4 characters).');
    }
    const duplicateCompleted = existingSettlements.find(
      (s) =>
        s.id !== settlement.id &&
        s.status === 'COMPLETED' &&
        s.paymentReference.trim().toLowerCase() === cleanRef.toLowerCase()
    );
    if (duplicateCompleted) {
      throw new Error(
        `Duplicate payment reference "${cleanRef}" was already used for settlement ${duplicateCompleted.id}.`
      );
    }
    return {
      ...settlement,
      status: 'AWAITING_CONFIRMATION',
      paymentMethod: params.paymentMethod || settlement.paymentMethod,
      paymentApp: params.paymentApp || settlement.paymentApp || 'UPI',
      paymentReference: cleanRef,
      payerConfirmedAtMillis: now,
      updatedAtMillis: now,
    };
  }

  if (action === 'RECEIVER_CONFIRM') {
    if (actorUid !== settlement.receiverUid) {
      throw new Error('Only the receiving member can confirm settlement receipt.');
    }
    if (settlement.status !== 'AWAITING_CONFIRMATION' && settlement.status !== 'PAYMENT_INITIATED') {
      throw new Error(
        'Payment must be initiated or awaiting confirmation before receiver can mark it Completed.'
      );
    }
    const cleanRef = (params.paymentReference || settlement.paymentReference || '').trim();
    if (cleanRef) {
      const dup = existingSettlements.find(
        (s) =>
          s.id !== settlement.id &&
          s.status === 'COMPLETED' &&
          s.paymentReference.trim().toLowerCase() === cleanRef.toLowerCase()
      );
      if (dup) {
        throw new Error(
          `Duplicate payment confirmation blocked: reference "${cleanRef}" already completed.`
        );
      }
    }
    return {
      ...settlement,
      status: 'COMPLETED',
      paymentReference: cleanRef,
      receiverConfirmedAtMillis: now,
      updatedAtMillis: now,
    };
  }

  if (action === 'MARK_FAILED') {
    if (actorUid !== settlement.payerUid && actorUid !== settlement.receiverUid) {
      throw new Error('Unauthorized actor for settlement failure update.');
    }
    return {
      ...settlement,
      status: 'FAILED',
      note: params.paymentReference
        ? `${settlement.note} · Failed (${params.paymentReference})`
        : settlement.note,
      updatedAtMillis: now,
    };
  }

  // REJECT
  if (actorUid !== settlement.receiverUid && actorUid !== settlement.payerUid) {
    throw new Error('Unauthorized actor for settlement rejection.');
  }
  return {
    ...settlement,
    status: 'REJECTED',
    updatedAtMillis: now,
  };
}

export function reconcileOfflineExpenseSync(
  localExpenses: GroupExpense[],
  cloudExpenses: GroupExpense[]
): {
  mergedExpenses: GroupExpense[];
  pendingToPush: GroupExpense[];
  pendingToDeleteIds: string[];
} {
  const pendingCreateOrUpdate = localExpenses.filter(
    (e) => e.pendingSync && (e.syncAction === 'CREATE' || e.syncAction === 'UPDATE') && !e.deleted
  );
  const pendingDeletes = localExpenses.filter(
    (e) => e.pendingSync && (e.syncAction === 'DELETE' || e.deleted)
  );
  const deletedIdSet = new Set(pendingDeletes.map((d) => d.id));

  const mergedMap = new Map<string, GroupExpense>();
  for (const c of cloudExpenses) {
    if (!deletedIdSet.has(c.id)) {
      mergedMap.set(c.id, { ...c, pendingSync: false });
    }
  }
  for (const p of pendingCreateOrUpdate) {
    const existing = mergedMap.get(p.id);
    if (!existing || p.updatedAtMillis >= existing.updatedAtMillis) {
      mergedMap.set(p.id, p);
    }
  }

  const mergedExpenses = Array.from(mergedMap.values()).sort(
    (a, b) => b.timestampMillis - a.timestampMillis
  );

  return {
    mergedExpenses,
    pendingToPush: pendingCreateOrUpdate,
    pendingToDeleteIds: Array.from(deletedIdSet),
  };
}

export const SETTLEMENT_STATUS_VALUES: SettlementStatus[] = [
  'PENDING',
  'PAYMENT_INITIATED',
  'AWAITING_CONFIRMATION',
  'COMPLETED',
  'REJECTED',
  'FAILED',
];
