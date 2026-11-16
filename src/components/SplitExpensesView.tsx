import React, { useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  Bell,
  Calendar,
  Check,
  CheckCircle2,
  Copy,
  CreditCard,
  Edit3,
  ExternalLink,
  Home,
  LogOut,
  Mail,
  MessageSquare,
  Plane,
  Plus,
  QrCode,
  Receipt,
  Share2,
  ShieldCheck,
  Trash2,
  UserPlus,
  Users,
  X,
  AlertTriangle,
} from 'lucide-react';
import {
  EXPENSE_CATEGORIES,
  ExpenseCategory,
  GROUP_TYPES,
  Group,
  GroupExpense,
  GroupInvitation,
  GroupMember,
  GroupType,
  InviteMethod,
  PaymentRequest,
  Settlement,
  SettlementDueCategory,
  SimplifiedDebt,
  SplitType,
  UserProfile,
} from '../types/expense';
import {
  buildGroupInvitationShareText,
  buildUpiPaymentUri,
  calculateSplitShares,
  categorizeSettlementDue,
  computeGroupBalances,
  computeUserAggregateSplitStats,
  createSettlementWithRequest,
  generateAppUserCode,
  leaveGroupMemberState,
  maskPhoneNumber,
  transitionSettlementStatus,
  validateUpiId,
} from '../lib/splitEngine';
import { formatMoney, toTimestampMillis } from '../lib/ledgerEngine';
import { lookupUserDirectoryByAppCode, SplitWorkspaceLocalState } from '../lib/splitSync';
import { QrCodeModal } from './QrCodeModal';

interface SplitExpensesViewProps {
  profile: UserProfile;
  firebaseUid: string | null;
  splitState: SplitWorkspaceLocalState;
  onUpdateUpiProfile: (upiId: string, verified: boolean) => Promise<void> | void;
  onCreateGroup: (params: {
    name: string;
    groupType: GroupType;
    iconKey: string;
    settlementDueDate: string;
  }) => Promise<Group | null>;
  onJoinGroupByCode: (joinCode: string) => Promise<string | null>;
  onLeaveGroup: (group: Group) => Promise<string | null>;
  onSendInvitation: (inv: GroupInvitation) => Promise<string | null>;
  onRespondInvitation: (inv: GroupInvitation, decision: 'ACCEPT' | 'REJECT') => Promise<string | null>;
  onSaveGroupExpense: (expense: GroupExpense, isNew: boolean) => Promise<string | null>;
  onDeleteGroupExpense: (expense: GroupExpense) => Promise<void>;
  onCreateSettlement: (settlement: Settlement, request: PaymentRequest) => Promise<void>;
  onUpdateSettlement: (settlement: Settlement, paymentRequestId?: string) => Promise<void>;
  onRecordPersonalSettlementExpense?: (settlement: Settlement) => void;
}

const GROUP_ICON_MAP: Record<GroupType, typeof Home> = {
  Roommates: Home,
  Friends: Users,
  Family: Users,
  'Trip Group': Plane,
  'Custom Group': CreditCard,
};

export const SplitExpensesView: React.FC<SplitExpensesViewProps> = ({
  profile,
  firebaseUid,
  splitState,
  onUpdateUpiProfile,
  onCreateGroup,
  onJoinGroupByCode,
  onLeaveGroup,
  onSendInvitation,
  onRespondInvitation,
  onSaveGroupExpense,
  onDeleteGroupExpense,
  onCreateSettlement,
  onUpdateSettlement,
  onRecordPersonalSettlementExpense,
}) => {
  const activeUid = profile.uid;
  const myAppUserCode = profile.appUserCode || generateAppUserCode(activeUid);
  const currency = profile.currencySymbol;

  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [settlementFilterTab, setSettlementFilterTab] = useState<'ALL' | SettlementDueCategory>('ALL');
  const [statusBanner, setStatusBanner] = useState<string | null>(null);
  const [errorBanner, setErrorBanner] = useState<string | null>(null);

  // UPI Setup Form
  const [upiInput, setUpiInput] = useState(profile.upiId || '');
  const [upiSaving, setUpiSaving] = useState(false);

  // Modals
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);
  const [showJoinGroupModal, setShowJoinGroupModal] = useState(false);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [expenseModalState, setExpenseModalState] = useState<{
    open: boolean;
    editing: GroupExpense | null;
  }>({ open: false, editing: null });
  const [settleModalDebt, setSettleModalDebt] = useState<{
    group: Group;
    debt: SimplifiedDebt;
    existingSettlement?: Settlement;
  } | null>(null);
  const [qrModalState, setQrModalState] = useState<{
    open: boolean;
    mode: 'DISPLAY' | 'SCAN';
    upiUri?: string;
    receiverName?: string;
    receiverUpiId?: string;
    amount?: number;
    note?: string;
    transactionRef?: string;
    settlement?: Settlement;
  }>({ open: false, mode: 'SCAN' });

  // Create Group fields
  const [newGroupName, setNewGroupName] = useState('');
  const [newGroupType, setNewGroupType] = useState<GroupType>('Roommates');
  const [newGroupDueDate, setNewGroupDueDate] = useState('');

  // Join Group fields
  const [joinCodeInput, setJoinCodeInput] = useState('');

  // Invite Member fields
  const [inviteMethod, setInviteMethod] = useState<InviteMethod>('APP_ID');
  const [inviteTargetInput, setInviteTargetInput] = useState('');
  const [contactPermissionConfirmed, setContactPermissionConfirmed] = useState(false);
  const [externalSharePreview, setExternalSharePreview] = useState<{
    text: string;
    url: string;
    channelLabel: string;
  } | null>(null);

  // Expense modal fields
  const receiptFileRef = useRef<HTMLInputElement>(null);
  const [expPaidByUid, setExpPaidByUid] = useState(activeUid);
  const [expAmount, setExpAmount] = useState('');
  const [expDesc, setExpDesc] = useState('');
  const [expCategory, setExpCategory] = useState<ExpenseCategory>('Food');
  const [expCustomDetail, setExpCustomDetail] = useState('');
  const [expDate, setExpDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [expTime, setExpTime] = useState(() => new Date().toTimeString().slice(0, 5));
  const [expNote, setExpNote] = useState('');
  const [expReceiptRef, setExpReceiptRef] = useState('');
  const [expSplitType, setExpSplitType] = useState<SplitType>('EQUAL');
  const [expParticipants, setExpParticipants] = useState<string[]>([]);
  const [expCustomInputs, setExpCustomInputs] = useState<Record<string, string>>({});
  const [expOcrLoading, setExpOcrLoading] = useState(false);
  const [expFormError, setExpFormError] = useState<string | null>(null);

  // Settle Modal fields
  const [settleDueDate, setSettleDueDate] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() + 3);
    return d.toISOString().slice(0, 10);
  });
  const [settleReminder, setSettleReminder] = useState(true);
  const [settleUtrInput, setSettleUtrInput] = useState('');
  const [logToPersonalLedger, setLogToPersonalLedger] = useState(true);

  const selectedGroup = useMemo(
    () => splitState.groups.find((g) => g.id === selectedGroupId) || null,
    [splitState.groups, selectedGroupId]
  );

  const aggregateStats = useMemo(
    () =>
      computeUserAggregateSplitStats(
        activeUid,
        splitState.groups,
        splitState.membersByGroup,
        splitState.expenses,
        splitState.settlements
      ),
    [activeUid, splitState]
  );

  const pendingInvitations = useMemo(
    () =>
      splitState.invitations.filter(
        (inv) =>
          inv.status === 'PENDING' &&
          inv.inviterUid !== activeUid &&
          (inv.targetUid === activeUid ||
            (profile.email &&
              inv.targetEmail &&
              inv.targetEmail.toLowerCase() === profile.email.toLowerCase()) ||
            inv.targetUid === '')
      ),
    [splitState.invitations, activeUid, profile.email]
  );

  const todayStr = new Date().toISOString().slice(0, 10);

  const filteredSettlements = useMemo(() => {
    const base = selectedGroup
      ? splitState.settlements.filter((s) => s.groupId === selectedGroup.id)
      : splitState.settlements;
    const sorted = [...base].sort((a, b) => b.updatedAtMillis - a.updatedAtMillis);
    if (settlementFilterTab === 'ALL') return sorted;
    return sorted.filter((s) => categorizeSettlementDue(s, todayStr) === settlementFilterTab);
  }, [splitState.settlements, selectedGroup, settlementFilterTab, todayStr]);

  const selectedGroupData = useMemo(() => {
    if (!selectedGroup) return null;
    const members = (splitState.membersByGroup[selectedGroup.id] || []).filter(
      (m) => m.status === 'ACTIVE'
    );
    const expenses = splitState.expenses
      .filter((e) => e.groupId === selectedGroup.id && !e.deleted)
      .sort((a, b) => b.timestampMillis - a.timestampMillis);
    const settlements = splitState.settlements.filter((s) => s.groupId === selectedGroup.id);
    const summary = computeGroupBalances(
      splitState.membersByGroup[selectedGroup.id] || [],
      expenses,
      settlements
    );
    return { members, expenses, settlements, summary };
  }, [selectedGroup, splitState]);

  const handleSaveUpi = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorBanner(null);
    const check = validateUpiId(upiInput);
    if (!check.valid) {
      setErrorBanner(check.error || 'Invalid UPI ID');
      return;
    }
    setUpiSaving(true);
    try {
      await onUpdateUpiProfile(check.normalized, true);
      setUpiInput(check.normalized);
      setStatusBanner(`Verified receiver UPI ID saved: ${check.normalized}`);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Could not update UPI ID');
    } finally {
      setUpiSaving(false);
    }
  };

  const handleCopyText = (val: string, label: string) => {
    navigator.clipboard?.writeText(val).catch(() => {});
    setStatusBanner(`${label} copied: ${val}`);
  };

  const handleCreateGroupSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newGroupName.trim()) return;
    const created = await onCreateGroup({
      name: newGroupName.trim(),
      groupType: newGroupType,
      iconKey: newGroupType.toLowerCase(),
      settlementDueDate: newGroupDueDate,
    });
    if (created) {
      setSelectedGroupId(created.id);
      setNewGroupName('');
      setNewGroupDueDate('');
      setShowCreateGroupModal(false);
      setStatusBanner(`Created group "${created.name}" (${created.joinCode}).`);
    }
  };

  const handleJoinGroupSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorBanner(null);
    const err = await onJoinGroupByCode(joinCodeInput.trim());
    if (err) {
      setErrorBanner(err);
    } else {
      setJoinCodeInput('');
      setShowJoinGroupModal(false);
      setStatusBanner('Successfully joined group.');
    }
  };

  const handleSendInviteSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedGroup) return;
    setErrorBanner(null);
    setExternalSharePreview(null);

    if (
      (inviteMethod === 'EMAIL' || inviteMethod === 'SMS' || inviteMethod === 'WHATSAPP') &&
      !contactPermissionConfirmed
    ) {
      setErrorBanner('Please confirm explicit contact-sharing permission before sending an external invite.');
      return;
    }

    const input = inviteTargetInput.trim();
    let targetUid = '';
    let targetAppCode = '';
    let targetEmail = '';
    let targetPhoneMasked = '';

    if (inviteMethod === 'APP_ID') {
      if (!input) {
        setErrorBanner('Enter a Smart Expense Manager User ID (e.g. SEM-XXXXXXX).');
        return;
      }
      if (firebaseUid) {
        const found = await lookupUserDirectoryByAppCode(input);
        if (found) {
          targetUid = found.uid;
          targetAppCode = found.appUserCode;
        } else {
          targetAppCode = input.toUpperCase();
        }
      } else {
        targetAppCode = input.toUpperCase();
      }
    } else if (inviteMethod === 'EMAIL') {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input)) {
        setErrorBanner('Enter a valid registered email address.');
        return;
      }
      targetEmail = input.toLowerCase();
    } else if (inviteMethod === 'SMS') {
      const digits = input.replace(/\D/g, '');
      if (digits.length < 7) {
        setErrorBanner('Enter a valid phone number for SMS invitation.');
        return;
      }
      targetPhoneMasked = maskPhoneNumber(input);
    }

    const now = Date.now();
    const token = `INV-${now.toString(36).toUpperCase().slice(-6)}`;
    const inv: GroupInvitation = {
      id: `inv_${now}_${Math.random().toString(36).slice(2, 6)}`,
      groupId: selectedGroup.id,
      groupName: selectedGroup.name,
      groupType: selectedGroup.groupType,
      inviterUid: activeUid,
      inviterName: profile.displayName,
      inviteMethod,
      targetUid,
      targetAppCode,
      targetEmail,
      targetPhoneMasked,
      inviteToken: token,
      status: 'PENDING',
      createdAtMillis: now,
      updatedAtMillis: now,
    };

    const err = await onSendInvitation(inv);
    if (err) {
      setErrorBanner(err);
      return;
    }

    const shareMsg = buildGroupInvitationShareText({
      inviterName: profile.displayName,
      groupName: selectedGroup.name,
      groupType: selectedGroup.groupType,
      joinCode: selectedGroup.joinCode,
      inviteToken: token,
      appUrl: window.location.origin,
    });

    if (inviteMethod === 'WHATSAPP') {
      setExternalSharePreview({
        text: shareMsg,
        url: `https://wa.me/?text=${encodeURIComponent(shareMsg)}`,
        channelLabel: 'Open WhatsApp Share Sheet',
      });
    } else if (inviteMethod === 'SMS') {
      const digits = input.replace(/[^0-9+]/g, '');
      setExternalSharePreview({
        text: shareMsg,
        url: `sms:${digits}?body=${encodeURIComponent(shareMsg)}`,
        channelLabel: 'Confirm & Open SMS Composer',
      });
    } else {
      setShowInviteModal(false);
      setInviteTargetInput('');
      setContactPermissionConfirmed(false);
      setStatusBanner(`Invitation sent via ${inviteMethod.replace('_', ' ')} (Token: ${token}).`);
    }
  };

  const openAddExpenseModal = (editing: GroupExpense | null = null) => {
    if (!selectedGroup || !selectedGroupData) return;
    const activeMembers = selectedGroupData.members.map((m) => m.uid);
    if (editing) {
      setExpPaidByUid(editing.paidByUid);
      setExpAmount(String(editing.amount));
      setExpDesc(editing.description);
      setExpCategory(editing.category);
      setExpCustomDetail(editing.customCategoryDetail || '');
      setExpDate(editing.transactionDate);
      setExpTime(editing.transactionTime);
      setExpNote(editing.note || '');
      setExpReceiptRef(editing.receiptReference || '');
      setExpSplitType(editing.splitType);
      setExpParticipants(editing.participantUids);
      const inputs: Record<string, string> = {};
      for (const uid of editing.participantUids) {
        const val = editing.shareInputs?.[uid] ?? editing.shares[uid];
        inputs[uid] = val !== undefined ? String(val) : '';
      }
      setExpCustomInputs(inputs);
    } else {
      setExpPaidByUid(activeUid);
      setExpAmount('');
      setExpDesc('');
      setExpCategory('Food');
      setExpCustomDetail('');
      setExpDate(new Date().toISOString().slice(0, 10));
      setExpTime(new Date().toTimeString().slice(0, 5));
      setExpNote('');
      setExpReceiptRef('');
      setExpSplitType('EQUAL');
      setExpParticipants(activeMembers);
      setExpCustomInputs({});
    }
    setExpFormError(null);
    setExpenseModalState({ open: true, editing });
  };

  const handleGroupReceiptUpload = async (file: File) => {
    setExpOcrLoading(true);
    setExpFormError(null);
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const res = String(reader.result || '');
        const base64 = res.includes(',') ? res.split(',')[1] : res;
        const resp = await fetch('/api/receipt-ocr', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ imageBase64: base64, mimeType: file.type || 'image/jpeg' }),
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.error || 'Receipt scan failed');
        if (data.amount) setExpAmount(String(data.amount));
        if (data.merchantName) setExpDesc(String(data.merchantName));
        if (data.transactionDate) setExpDate(String(data.transactionDate));
        if (data.transactionTime) setExpTime(String(data.transactionTime));
        if (data.referenceId) setExpReceiptRef(String(data.referenceId));
        if (data.suggestedCategory && EXPENSE_CATEGORIES.includes(data.suggestedCategory)) {
          setExpCategory(data.suggestedCategory);
        }
      } catch (err) {
        setExpFormError(err instanceof Error ? err.message : 'Unable to scan receipt');
      } finally {
        setExpOcrLoading(false);
      }
    };
    reader.readAsDataURL(file);
  };

  const liveSplitPreview = useMemo(() => {
    const amt = Number(expAmount);
    const numericInputs: Record<string, number> = {};
    for (const uid of expParticipants) {
      numericInputs[uid] = Number(expCustomInputs[uid] || 0);
    }
    return calculateSplitShares(amt, expSplitType, expParticipants, numericInputs);
  }, [expAmount, expSplitType, expParticipants, expCustomInputs]);

  const handleSaveExpenseSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedGroup || !selectedGroupData) return;
    setExpFormError(null);

    const amt = Number(expAmount);
    if (!Number.isFinite(amt) || amt <= 0) {
      setExpFormError('Enter a valid amount greater than zero.');
      return;
    }
    if (!expDesc.trim()) {
      setExpFormError('Enter a description.');
      return;
    }
    if (expCategory === 'Other' && !expCustomDetail.trim()) {
      setExpFormError('Please specify "Where have you spent this money?" for the Other category.');
      return;
    }
    if (!liveSplitPreview.valid) {
      setExpFormError(liveSplitPreview.error || 'Invalid split distribution.');
      return;
    }

    const payerMember = selectedGroupData.members.find((m) => m.uid === expPaidByUid);
    const now = Date.now();
    const isNew = !expenseModalState.editing;
    const shareInputsNumeric: Record<string, number> = {};
    for (const uid of expParticipants) {
      shareInputsNumeric[uid] = Number(expCustomInputs[uid] || 0);
    }

    const expense: GroupExpense = {
      id: expenseModalState.editing?.id || `gexp_${now}_${Math.random().toString(36).slice(2, 6)}`,
      groupId: selectedGroup.id,
      memberUids: selectedGroup.memberUids,
      paidByUid: expPaidByUid,
      paidByName: payerMember?.displayName || profile.displayName,
      amount: Math.round(amt * 100) / 100,
      description: expDesc.trim(),
      category: expCategory,
      customCategoryDetail: expCategory === 'Other' ? expCustomDetail.trim() : '',
      transactionDate: expDate,
      transactionTime: expTime,
      timestampMillis: toTimestampMillis(expDate, expTime),
      splitType: expSplitType,
      participantUids: expParticipants,
      shares: liveSplitPreview.shares,
      shareInputs: shareInputsNumeric,
      receiptReference: expReceiptRef.trim(),
      note: expNote.trim(),
      createdAtMillis: expenseModalState.editing?.createdAtMillis || now,
      updatedAtMillis: now,
    };

    const err = await onSaveGroupExpense(expense, isNew);
    if (err) {
      setExpFormError(err);
    } else {
      setExpenseModalState({ open: false, editing: null });
      setStatusBanner(isNew ? 'Group expense added and balances updated.' : 'Group expense updated.');
    }
  };

  const openSettleNowForDebt = async (group: Group, debt: SimplifiedDebt) => {
    setErrorBanner(null);
    // Check if there's an existing unsettled settlement matching this debt
    const existing = splitState.settlements.find(
      (s) =>
        s.groupId === group.id &&
        s.payerUid === debt.fromUid &&
        s.receiverUid === debt.toUid &&
        s.status !== 'COMPLETED' &&
        s.status !== 'REJECTED'
    );

    if (existing) {
      setSettleUtrInput(existing.paymentReference || '');
      setSettleDueDate(existing.dueDate || settleDueDate);
      setSettleModalDebt({ group, debt, existingSettlement: existing });
      return;
    }

    const now = Date.now();
    const { settlement, paymentRequest } = createSettlementWithRequest({
      id: `settle_${now}_${Math.random().toString(36).slice(2, 6)}`,
      groupId: group.id,
      groupName: group.name,
      memberUids: group.memberUids,
      payerUid: debt.fromUid,
      payerName: debt.fromName,
      receiverUid: debt.toUid,
      receiverName: debt.toName,
      receiverUpiId: debt.toUpiId,
      amount: debt.amount,
      dueDate: group.settlementDueDate || settleDueDate,
      reminderEnabled: settleReminder,
      paymentMethod: 'UPI',
      note: `${group.name} balance settlement`,
      nowMillis: now,
    });

    await onCreateSettlement(settlement, paymentRequest);
    setSettleUtrInput('');
    setSettleModalDebt({ group, debt, existingSettlement: settlement });
  };

  const activeModalSettlement = useMemo(() => {
    if (!settleModalDebt?.existingSettlement) return null;
    return (
      splitState.settlements.find((s) => s.id === settleModalDebt.existingSettlement!.id) ||
      settleModalDebt.existingSettlement
    );
  }, [settleModalDebt, splitState.settlements]);

  const handleSettlementTransition = async (
    settlement: Settlement,
    action: 'INITIATE_UPI' | 'SUBMIT_PAYER_PROOF' | 'RECEIVER_CONFIRM' | 'REJECT' | 'MARK_FAILED',
    opts?: { appName?: string; method?: Settlement['paymentMethod']; utr?: string; actorUid?: string }
  ) => {
    setErrorBanner(null);
    try {
      const updated = transitionSettlementStatus({
        settlement,
        existingSettlements: splitState.settlements,
        actorUid: opts?.actorUid || activeUid,
        action,
        paymentApp: opts?.appName,
        paymentMethod: opts?.method,
        paymentReference: opts?.utr,
      });
      const req = splitState.paymentRequests.find((r) => r.settlementId === settlement.id);
      await onUpdateSettlement(updated, req?.id);
      if (updated.status === 'COMPLETED' && logToPersonalLedger && onRecordPersonalSettlementExpense) {
        onRecordPersonalSettlementExpense(updated);
      }
      setStatusBanner(`Settlement updated to ${updated.status.replace('_', ' ')}.`);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Settlement action failed');
    }
  };

  const renderDueBadge = (cat: SettlementDueCategory) => {
    const map: Record<SettlementDueCategory, string> = {
      OVERDUE: 'text-[#EF4444]',
      DUE: 'text-[#FF6B2C]',
      UPCOMING: 'text-[#C6FF33]',
      COMPLETED: 'text-[#2CE59B]',
    };
    return <span className={`text-[11px] font-semibold ${map[cat]}`}>{cat}</span>;
  };

  return (
    <div className="space-y-6">
      {/* Header Row */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          {selectedGroup && (
            <button
              type="button"
              onClick={() => setSelectedGroupId(null)}
              className="min-h-[42px] px-3 rounded-xl bg-[#121519] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-1.5 cursor-pointer"
            >
              <ArrowLeft className="w-4 h-4" />
              <span>All Groups</span>
            </button>
          )}
          <div>
            <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
              {selectedGroup ? selectedGroup.name : 'SPLIT EXPENSES'}
            </h1>
            <p className="text-sm text-[#9CA3AF] mt-1">
              {selectedGroup
                ? `${selectedGroup.groupType} · Join Code: ${selectedGroup.joinCode} · Created ${new Date(selectedGroup.createdAtMillis).toLocaleDateString()}`
                : 'Roommate groups, shared expense splits, automatic balances, and verified UPI/QR settlements.'}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <button
            type="button"
            onClick={() => setQrModalState({ open: true, mode: 'SCAN' })}
            className="min-h-[42px] px-4 py-2 rounded-xl bg-[#121519] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/60 inline-flex items-center gap-2 cursor-pointer"
          >
            <QrCode className="w-4 h-4 text-[#C6FF33]" />
            <span>Scan UPI QR</span>
          </button>
          <button
            type="button"
            onClick={() => setShowJoinGroupModal(true)}
            className="min-h-[42px] px-4 py-2 rounded-xl bg-[#121519] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/60 inline-flex items-center gap-2 cursor-pointer"
          >
            <UserPlus className="w-4 h-4 text-[#2CE59B]" />
            <span>Join Group</span>
          </button>
          <button
            type="button"
            onClick={() => setShowCreateGroupModal(true)}
            className="min-h-[42px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] inline-flex items-center gap-2 cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Create New Group</span>
          </button>
        </div>
      </div>

      {statusBanner && (
        <div className="p-3.5 rounded-xl bg-[#2CE59B]/10 border border-[#2CE59B]/35 text-xs text-[#2CE59B] flex items-center justify-between gap-3">
          <span>{statusBanner}</span>
          <button type="button" onClick={() => setStatusBanner(null)} className="cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {errorBanner && (
        <div className="p-3.5 rounded-xl bg-[#EF4444]/10 border border-[#EF4444]/35 text-xs text-[#FCA5A5] flex items-center justify-between gap-3">
          <span>{errorBanner}</span>
          <button type="button" onClick={() => setErrorBanner(null)} className="cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {!selectedGroup ? (
        <>
          {/* Dashboard Summary KPI Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
              <p className="text-xs font-medium text-[#9CA3AF]">Total Group Expenses</p>
              <p className="text-2xl font-bold font-mono-num text-[#F3F5F7] mt-2">
                {formatMoney(aggregateStats.totalGroupExpenses, currency)}
              </p>
            </div>
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
              <p className="text-xs font-medium text-[#9CA3AF]">My Outstanding Balance</p>
              <p className="text-2xl font-bold font-mono-num text-[#FF6B2C] mt-2">
                {formatMoney(aggregateStats.myOutstandingOwed, currency)}
              </p>
            </div>
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
              <p className="text-xs font-medium text-[#9CA3AF]">Amount Others Owe Me</p>
              <p className="text-2xl font-bold font-mono-num text-[#2CE59B] mt-2">
                {formatMoney(aggregateStats.othersOweMe, currency)}
              </p>
            </div>
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5">
              <p className="text-xs font-medium text-[#9CA3AF]">My Smart User ID</p>
              <div className="flex items-center justify-between gap-2 mt-2">
                <p className="text-base font-bold font-mono-num text-[#C6FF33] truncate">
                  {myAppUserCode}
                </p>
                <button
                  type="button"
                  onClick={() => handleCopyText(myAppUserCode, 'Smart Expense Manager User ID')}
                  className="p-2 rounded-lg bg-[#181C22] border border-white/10 text-[#9CA3AF] hover:text-white cursor-pointer"
                  aria-label="Copy User ID"
                >
                  <Copy className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          </div>

          {/* Receiver UPI ID Setup & Verification */}
          <form
            onSubmit={handleSaveUpi}
            className="bg-[#121519] border border-white/10 rounded-2xl p-5 flex flex-col md:flex-row md:items-end justify-between gap-4"
          >
            <div className="flex-1 space-y-1.5">
              <div className="flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-[#C6FF33]" />
                <label htmlFor="my-upi-id" className="text-sm font-semibold text-[#F3F5F7]">
                  My Receiver UPI ID (for receiving group settlements)
                </label>
                {profile.upiVerified && profile.upiId && (
                  <span className="text-xs font-medium text-[#2CE59B] inline-flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" /> Verified Format
                  </span>
                )}
              </div>
              <p className="text-xs text-[#9CA3AF]">
                Used strictly to pre-fill standard <span className="font-mono-num">upi://pay</span> requests and dynamic payment QR codes. Never enter UPI PINs, OTPs, or banking passwords.
              </p>
            </div>
            <div className="flex items-center gap-2.5 w-full md:w-auto">
              <input
                id="my-upi-id"
                type="text"
                value={upiInput}
                onChange={(e) => setUpiInput(e.target.value)}
                placeholder="yourname@okaxis"
                className="flex-1 md:w-64 min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
              <button
                type="submit"
                disabled={upiSaving}
                className="min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] whitespace-nowrap cursor-pointer"
              >
                {upiSaving ? 'Verifying...' : 'Verify & Save UPI'}
              </button>
            </div>
          </form>

          {/* Pending Invitations */}
          {pendingInvitations.length > 0 && (
            <div className="bg-[#121519] border border-[#C6FF33]/40 rounded-2xl p-5 space-y-3">
              <div className="flex items-center gap-2">
                <Bell className="w-4 h-4 text-[#C6FF33]" />
                <h2 className="text-base font-semibold text-[#F3F5F7]">
                  Pending Group Invitations ({pendingInvitations.length})
                </h2>
              </div>
              <div className="space-y-2.5">
                {pendingInvitations.map((inv) => (
                  <div
                    key={inv.id}
                    className="p-4 rounded-xl bg-[#0B0D0F] border border-white/10 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
                  >
                    <div>
                      <p className="text-sm font-semibold text-[#F3F5F7]">
                        {inv.groupName}{' '}
                        <span className="text-xs font-normal text-[#9CA3AF]">({inv.groupType})</span>
                      </p>
                      <p className="text-xs text-[#9CA3AF] mt-0.5">
                        Invited by <span className="text-[#F3F5F7]">{inv.inviterName}</span> via{' '}
                        {inv.inviteMethod.replace('_', ' ')} · Token:{' '}
                        <span className="font-mono-num text-[#C6FF33]">{inv.inviteToken}</span>
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={async () => {
                          const err = await onRespondInvitation(inv, 'REJECT');
                          if (err) setErrorBanner(err);
                          else setStatusBanner(`Rejected invitation to ${inv.groupName}.`);
                        }}
                        className="min-h-[40px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#9CA3AF] hover:text-white cursor-pointer"
                      >
                        Reject
                      </button>
                      <button
                        type="button"
                        onClick={async () => {
                          const err = await onRespondInvitation(inv, 'ACCEPT');
                          if (err) setErrorBanner(err);
                          else {
                            setStatusBanner(`Joined group "${inv.groupName}".`);
                            setSelectedGroupId(inv.groupId);
                          }
                        }}
                        className="min-h-[40px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] cursor-pointer"
                      >
                        Accept &amp; Join
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* My Groups */}
          <div className="space-y-3">
            <h2 className="text-base font-semibold text-[#F3F5F7]">My Groups</h2>
            {splitState.groups.length === 0 ? (
              <div className="bg-[#121519] border border-white/10 rounded-2xl p-10 text-center space-y-3">
                <Users className="w-8 h-8 text-[#C6FF33] mx-auto" />
                <p className="text-sm font-semibold text-[#F3F5F7]">No roommate or friend groups yet</p>
                <p className="text-xs text-[#9CA3AF] max-w-md mx-auto">
                  Create a Roommates, Friends, Family, Trip, or Custom group to track shared expenses and settle balances over UPI or QR.
                </p>
                <button
                  type="button"
                  onClick={() => setShowCreateGroupModal(true)}
                  className="min-h-[42px] px-5 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] cursor-pointer"
                >
                  Create Your First Group
                </button>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {splitState.groups.map((grp) => {
                  const Icon = GROUP_ICON_MAP[grp.groupType] || Users;
                  const members = (splitState.membersByGroup[grp.id] || []).filter(
                    (m) => m.status === 'ACTIVE'
                  );
                  const grpExpenses = splitState.expenses.filter(
                    (e) => e.groupId === grp.id && !e.deleted
                  );
                  const grpSettlements = splitState.settlements.filter((s) => s.groupId === grp.id);
                  const grpSummary = computeGroupBalances(
                    splitState.membersByGroup[grp.id] || [],
                    grpExpenses,
                    grpSettlements
                  );
                  const myBal = grpSummary.memberBalances.find((m) => m.uid === activeUid)?.netBalance || 0;

                  return (
                    <button
                      key={grp.id}
                      type="button"
                      onClick={() => setSelectedGroupId(grp.id)}
                      className="text-left bg-[#121519] border border-white/10 hover:border-[#C6FF33]/60 rounded-2xl p-5 space-y-4 transition-colors cursor-pointer"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 rounded-xl bg-[#C6FF33]/15 text-[#C6FF33] flex items-center justify-center">
                            <Icon className="w-5 h-5" />
                          </div>
                          <div>
                            <h3 className="text-sm font-semibold text-[#F3F5F7]">{grp.name}</h3>
                            <p className="text-xs text-[#9CA3AF]">
                              {grp.groupType} · {members.length || grp.memberUids.length} members
                            </p>
                          </div>
                        </div>
                        <span className="text-[11px] font-mono-num text-[#9CA3AF]">{grp.joinCode}</span>
                      </div>

                      <div className="grid grid-cols-2 gap-3 pt-3 border-t border-white/10">
                        <div>
                          <p className="text-[11px] text-[#9CA3AF]">Total Shared</p>
                          <p className="text-sm font-semibold font-mono-num text-[#F3F5F7] mt-0.5">
                            {formatMoney(grpSummary.totalGroupExpenses, currency)}
                          </p>
                        </div>
                        <div className="text-right">
                          <p className="text-[11px] text-[#9CA3AF]">My Net Balance</p>
                          <p
                            className={`text-sm font-semibold font-mono-num mt-0.5 ${
                              myBal > 0.005
                                ? 'text-[#2CE59B]'
                                : myBal < -0.005
                                  ? 'text-[#FF6B2C]'
                                  : 'text-[#9CA3AF]'
                            }`}
                          >
                            {myBal > 0.005
                              ? `+${formatMoney(myBal, currency)}`
                              : myBal < -0.005
                                ? `-${formatMoney(Math.abs(myBal), currency)}`
                                : 'Settled'}
                          </p>
                        </div>
                      </div>
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        </>
      ) : (
        selectedGroupData && (
          <div className="space-y-6">
            {/* Group Action Bar */}
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5 flex flex-col lg:flex-row lg:items-center justify-between gap-4">
              <div className="flex flex-wrap items-center gap-3">
                <button
                  type="button"
                  onClick={() => openAddExpenseModal(null)}
                  className="min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] inline-flex items-center gap-2 cursor-pointer"
                >
                  <Plus className="w-4 h-4" />
                  <span>Add Group Expense</span>
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setExternalSharePreview(null);
                    setShowInviteModal(true);
                  }}
                  className="min-h-[44px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/50 inline-flex items-center gap-2 cursor-pointer"
                >
                  <UserPlus className="w-4 h-4 text-[#C6FF33]" />
                  <span>Invite Member</span>
                </button>
                <button
                  type="button"
                  onClick={() =>
                    handleCopyText(selectedGroup.joinCode, 'Group Join Code')
                  }
                  className="min-h-[44px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-mono-num text-[#9CA3AF] hover:text-white inline-flex items-center gap-2 cursor-pointer"
                >
                  <Copy className="w-3.5 h-3.5" />
                  <span>{selectedGroup.joinCode}</span>
                </button>
              </div>

              <div className="flex items-center gap-4">
                <div className="text-right">
                  <p className="text-[11px] text-[#9CA3AF]">Group Total</p>
                  <p className="text-lg font-bold font-mono-num text-[#F3F5F7]">
                    {formatMoney(selectedGroupData.summary.totalGroupExpenses, currency)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={async () => {
                    const check = leaveGroupMemberState(
                      selectedGroup,
                      splitState.membersByGroup[selectedGroup.id] || [],
                      activeUid,
                      selectedGroupData.expenses,
                      selectedGroupData.settlements
                    );
                    if (!check.allowed) {
                      setErrorBanner(check.reason || 'Cannot leave group with unsettled balance.');
                      return;
                    }
                    const err = await onLeaveGroup(selectedGroup);
                    if (err) setErrorBanner(err);
                    else {
                      setSelectedGroupId(null);
                      setStatusBanner(`Left group "${selectedGroup.name}".`);
                    }
                  }}
                  className="min-h-[42px] px-3.5 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#FCA5A5] hover:bg-[#EF4444]/15 inline-flex items-center gap-1.5 cursor-pointer"
                >
                  <LogOut className="w-3.5 h-3.5" />
                  <span>Leave Group</span>
                </button>
              </div>
            </div>

            {/* Member Balances & Simplified Settlement Suggestions */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              <div className="lg:col-span-6 bg-[#121519] border border-white/10 rounded-2xl p-5 space-y-4">
                <h2 className="text-base font-semibold text-[#F3F5F7]">
                  Member Contributions &amp; Balances
                </h2>
                <div className="space-y-2.5">
                  {selectedGroupData.summary.memberBalances
                    .filter((m) => m.status === 'ACTIVE')
                    .map((mb) => (
                      <div
                        key={mb.uid}
                        className="p-3.5 rounded-xl bg-[#0B0D0F] border border-white/10 flex items-center justify-between gap-3"
                      >
                        <div>
                          <p className="text-sm font-medium text-[#F3F5F7]">
                            {mb.displayName}{' '}
                            {mb.uid === activeUid && (
                              <span className="text-xs text-[#C6FF33]">(You)</span>
                            )}
                            {mb.role === 'ADMIN' && (
                              <span className="text-[11px] text-[#9CA3AF] ml-1.5">· Admin</span>
                            )}
                          </p>
                          <p className="text-xs text-[#9CA3AF] mt-0.5 font-mono-num">
                            Paid {formatMoney(mb.totalPaid, currency)} · Share{' '}
                            {formatMoney(mb.totalFairShare, currency)}
                          </p>
                        </div>
                        <div className="text-right">
                          <p
                            className={`text-sm font-bold font-mono-num ${
                              mb.netBalance > 0.005
                                ? 'text-[#2CE59B]'
                                : mb.netBalance < -0.005
                                  ? 'text-[#FF6B2C]'
                                  : 'text-[#9CA3AF]'
                            }`}
                          >
                            {mb.netBalance > 0.005
                              ? `Gets +${formatMoney(mb.netBalance, currency)}`
                              : mb.netBalance < -0.005
                                ? `Owes ${formatMoney(Math.abs(mb.netBalance), currency)}`
                                : 'Settled'}
                          </p>
                          {mb.upiId && (
                            <p className="text-[11px] font-mono-num text-[#6B7280]">{mb.upiId}</p>
                          )}
                        </div>
                      </div>
                    ))}
                </div>
              </div>

              {/* Simplified Settlement Plan with SETTLE NOW button */}
              <div className="lg:col-span-6 bg-[#121519] border border-white/10 rounded-2xl p-5 space-y-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-base font-semibold text-[#F3F5F7]">
                    Outstanding Balances to Settle
                  </h2>
                  {selectedGroup.settlementDueDate && (
                    <span className="text-xs text-[#9CA3AF] inline-flex items-center gap-1">
                      <Calendar className="w-3.5 h-3.5 text-[#C6FF33]" />
                      Due {selectedGroup.settlementDueDate}
                    </span>
                  )}
                </div>

                {selectedGroupData.summary.simplifiedDebts.length === 0 ? (
                  <div className="py-10 text-center space-y-1.5">
                    <CheckCircle2 className="w-7 h-7 text-[#2CE59B] mx-auto" />
                    <p className="text-sm font-medium text-[#F3F5F7]">All balances are settled</p>
                    <p className="text-xs text-[#9CA3AF]">
                      Add a shared expense to calculate automatic splits and debts.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {selectedGroupData.summary.simplifiedDebts.map((debt, idx) => (
                      <div
                        key={`${debt.fromUid}_${debt.toUid}_${idx}`}
                        className="p-4 rounded-xl bg-[#0B0D0F] border border-white/10 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
                      >
                        <div>
                          <p className="text-sm font-semibold text-[#F3F5F7]">
                            {debt.fromName} <span className="text-[#9CA3AF]">owes</span>{' '}
                            {debt.toName}
                          </p>
                          <p className="text-lg font-bold font-mono-num text-[#FF6B2C] mt-0.5">
                            {formatMoney(debt.amount, currency)}
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => openSettleNowForDebt(selectedGroup, debt)}
                          className="min-h-[42px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] cursor-pointer whitespace-nowrap"
                        >
                          SETTLE NOW
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Shared Group Ledger */}
            <div className="bg-[#121519] border border-white/10 rounded-2xl p-5 space-y-4">
              <div className="flex items-center justify-between">
                <h2 className="text-base font-semibold text-[#F3F5F7]">
                  Shared Group Ledger ({selectedGroupData.expenses.length})
                </h2>
                <button
                  type="button"
                  onClick={() => openAddExpenseModal(null)}
                  className="text-xs font-semibold text-[#C6FF33] hover:underline cursor-pointer"
                >
                  + Add Expense
                </button>
              </div>

              {selectedGroupData.expenses.length === 0 ? (
                <p className="text-xs text-[#9CA3AF] py-6 text-center">
                  No expenses logged in this group yet.
                </p>
              ) : (
                <div className="space-y-3">
                  {selectedGroupData.expenses.map((exp) => {
                    const memberNameMap = new Map(
                      (splitState.membersByGroup[selectedGroup.id] || []).map((m) => [
                        m.uid,
                        m.displayName,
                      ])
                    );
                    const catLabel =
                      exp.category === 'Other' && exp.customCategoryDetail
                        ? `Other — ${exp.customCategoryDetail}`
                        : exp.category;
                    return (
                      <div
                        key={exp.id}
                        className="p-4 rounded-xl bg-[#0B0D0F] border border-white/10 space-y-2.5"
                      >
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                          <div>
                            <div className="flex items-center gap-2">
                              <p className="text-sm font-semibold text-[#F3F5F7]">
                                {exp.description}
                              </p>
                              {exp.pendingSync && (
                                <span className="text-[11px] font-medium text-[#FF6B2C]">
                                  (Offline Pending Sync)
                                </span>
                              )}
                            </div>
                            <p className="text-xs text-[#9CA3AF] mt-0.5">
                              Paid by <span className="text-[#F3F5F7]">{exp.paidByName}</span> ·{' '}
                              {catLabel} · {exp.transactionDate} {exp.transactionTime} ·{' '}
                              {exp.splitType.replace('_', ' ')}
                            </p>
                          </div>
                          <div className="flex items-center gap-3">
                            <p className="text-base font-bold font-mono-num text-[#FF6B2C]">
                              {formatMoney(exp.amount, currency)}
                            </p>
                            <button
                              type="button"
                              onClick={() => openAddExpenseModal(exp)}
                              aria-label="Edit group expense"
                              className="p-2 rounded-lg text-[#9CA3AF] hover:text-white hover:bg-white/5 cursor-pointer"
                            >
                              <Edit3 className="w-4 h-4" />
                            </button>
                            <button
                              type="button"
                              onClick={() => onDeleteGroupExpense(exp)}
                              aria-label="Delete group expense"
                              className="p-2 rounded-lg text-[#9CA3AF] hover:text-[#EF4444] hover:bg-white/5 cursor-pointer"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </div>
                        </div>

                        <div className="pt-2 border-t border-white/5 flex flex-wrap gap-3 text-xs">
                          {Object.entries(exp.shares).map(([uid, shareAmt]) => (
                            <span key={uid} className="text-[#9CA3AF] font-mono-num">
                              {memberNameMap.get(uid) || uid}:{' '}
                              <span className="text-[#F3F5F7]">
                                {formatMoney(shareAmt, currency)}
                              </span>
                            </span>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        )
      )}

      {/* Settlement Requests & Settlement History Section (Dashboard & Group View) */}
      <div className="bg-[#121519] border border-white/10 rounded-2xl p-5 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Settlement Requests &amp; History
            </h2>
            <p className="text-xs text-[#9CA3AF]">
              Track Upcoming, Due, Overdue, and Completed settlements with dual-party verification.
            </p>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {(['ALL', 'UPCOMING', 'DUE', 'OVERDUE', 'COMPLETED'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                onClick={() => setSettlementFilterTab(tab)}
                className={`min-h-[34px] px-3 py-1 rounded-lg text-xs font-medium cursor-pointer transition-colors ${
                  settlementFilterTab === tab
                    ? 'bg-[#C6FF33] text-[#0B0D0F] font-semibold'
                    : 'bg-[#0B0D0F] border border-white/10 text-[#9CA3AF] hover:text-white'
                }`}
              >
                {tab === 'ALL' ? 'All' : tab.charAt(0) + tab.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>

        {filteredSettlements.length === 0 ? (
          <p className="text-xs text-[#9CA3AF] py-6 text-center">
            No settlements in this category yet.
          </p>
        ) : (
          <div className="space-y-3">
            {filteredSettlements.map((s) => {
              const dueCat = categorizeSettlementDue(s, todayStr);
              const isPayer = s.payerUid === activeUid;
              const isReceiver = s.receiverUid === activeUid;
              const upiUri = s.receiverUpiId
                ? buildUpiPaymentUri({
                    upiId: s.receiverUpiId,
                    payeeName: s.receiverName,
                    amount: s.amount,
                    note: s.note,
                    transactionRef: s.id,
                  })
                : '';

              return (
                <div
                  key={s.id}
                  className="p-4 rounded-xl bg-[#0B0D0F] border border-white/10 flex flex-col lg:flex-row lg:items-center justify-between gap-4"
                >
                  <div className="space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-sm font-semibold text-[#F3F5F7]">
                        {s.payerName} → {s.receiverName}
                      </p>
                      <span className="text-xs font-mono-num text-[#9CA3AF]">· {s.groupName}</span>
                      {renderDueBadge(dueCat)}
                    </div>
                    <p className="text-xs text-[#9CA3AF]">
                      Status:{' '}
                      <span className="font-semibold text-[#C6FF33]">
                        {s.status.replace('_', ' ')}
                      </span>
                      {s.dueDate ? ` · Due ${s.dueDate}` : ''}
                      {s.paymentReference ? ` · Ref: ${s.paymentReference}` : ''}
                      {s.paymentApp ? ` · ${s.paymentApp}` : ''}
                    </p>
                  </div>

                  <div className="flex flex-wrap items-center gap-2.5">
                    <span className="text-base font-bold font-mono-num text-[#F3F5F7] mr-2">
                      {formatMoney(s.amount, currency)}
                    </span>

                    {s.status !== 'COMPLETED' && isPayer && (
                      <button
                        type="button"
                        onClick={() => {
                          const grp = splitState.groups.find((g) => g.id === s.groupId);
                          if (!grp) return;
                          setSettleUtrInput(s.paymentReference || '');
                          setSettleModalDebt({
                            group: grp,
                            debt: {
                              fromUid: s.payerUid,
                              fromName: s.payerName,
                              toUid: s.receiverUid,
                              toName: s.receiverName,
                              toUpiId: s.receiverUpiId,
                              toUpiVerified: Boolean(s.receiverUpiId),
                              amount: s.amount,
                            },
                            existingSettlement: s,
                          });
                        }}
                        className="min-h-[38px] px-3.5 py-1.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold hover:bg-[#d4ff66] cursor-pointer"
                      >
                        SETTLE NOW
                      </button>
                    )}

                    {s.status !== 'COMPLETED' && s.receiverUpiId && (
                      <button
                        type="button"
                        onClick={() =>
                          setQrModalState({
                            open: true,
                            mode: 'DISPLAY',
                            upiUri,
                            receiverName: s.receiverName,
                            receiverUpiId: s.receiverUpiId,
                            amount: s.amount,
                            note: s.note,
                            transactionRef: s.id,
                            settlement: s,
                          })
                        }
                        className="min-h-[38px] px-3 py-1.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/60 inline-flex items-center gap-1.5 cursor-pointer"
                      >
                        <QrCode className="w-3.5 h-3.5 text-[#C6FF33]" />
                        <span>QR Code</span>
                      </button>
                    )}

                    {isReceiver &&
                      (s.status === 'AWAITING_CONFIRMATION' || s.status === 'PAYMENT_INITIATED') && (
                        <>
                          <button
                            type="button"
                            onClick={() => handleSettlementTransition(s, 'RECEIVER_CONFIRM')}
                            className="min-h-[38px] px-3.5 py-1.5 rounded-xl bg-[#2CE59B] text-[#0B0D0F] text-xs font-semibold cursor-pointer"
                          >
                            Confirm Receipt
                          </button>
                          <button
                            type="button"
                            onClick={() => handleSettlementTransition(s, 'REJECT')}
                            className="min-h-[38px] px-3 py-1.5 rounded-xl bg-[#181C22] border border-[#EF4444]/40 text-xs text-[#FCA5A5] cursor-pointer"
                          >
                            Reject
                          </button>
                        </>
                      )}

                    {s.status === 'PENDING' && s.reminderEnabled && (
                      <button
                        type="button"
                        onClick={() =>
                          setStatusBanner(
                            `Settlement reminder queued for ${s.payerName} (Due ${s.dueDate || 'soon'}).`
                          )
                        }
                        className="min-h-[38px] px-3 py-1.5 rounded-xl bg-[#181C22] border border-white/10 text-xs text-[#9CA3AF] hover:text-white inline-flex items-center gap-1.5 cursor-pointer"
                      >
                        <Bell className="w-3.5 h-3.5 text-[#C6FF33]" />
                        <span>Remind</span>
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Create Group Modal */}
      {showCreateGroupModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <form
            onSubmit={handleCreateGroupSubmit}
            className="w-full max-w-md bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4"
          >
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-[#F3F5F7]">Create New Group</h2>
              <button
                type="button"
                onClick={() => setShowCreateGroupModal(false)}
                className="p-2 text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Group Name *
              </label>
              <input
                type="text"
                required
                value={newGroupName}
                onChange={(e) => setNewGroupName(e.target.value)}
                placeholder="e.g., Koramangala Flat 302"
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Group Type
              </label>
              <select
                value={newGroupType}
                onChange={(e) => setNewGroupType(e.target.value as GroupType)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              >
                {GROUP_TYPES.map((gt) => (
                  <option key={gt} value={gt}>
                    {gt}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Default Settlement Due Date (Optional)
              </label>
              <input
                type="date"
                value={newGroupDueDate}
                onChange={(e) => setNewGroupDueDate(e.target.value)}
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
            <button
              type="submit"
              className="w-full min-h-[46px] rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              Create Group
            </button>
          </form>
        </div>
      )}

      {/* Join Group Modal */}
      {showJoinGroupModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <form
            onSubmit={handleJoinGroupSubmit}
            className="w-full max-w-md bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4"
          >
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-[#F3F5F7]">Join Group</h2>
              <button
                type="button"
                onClick={() => setShowJoinGroupModal(false)}
                className="p-2 text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div>
              <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                Group Join Code (e.g., GRP-A1B2C3) *
              </label>
              <input
                type="text"
                required
                value={joinCodeInput}
                onChange={(e) => setJoinCodeInput(e.target.value)}
                placeholder="GRP-XXXXXX"
                className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
              />
            </div>
            <button
              type="submit"
              className="w-full min-h-[46px] rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              Join Group
            </button>
          </form>
        </div>
      )}

      {/* Invite Member Modal */}
      {showInviteModal && selectedGroup && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
          <form
            onSubmit={handleSendInviteSubmit}
            className="w-full max-w-lg bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4 my-8"
          >
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-[#F3F5F7]">
                Invite Member to {selectedGroup.name}
              </h2>
              <button
                type="button"
                onClick={() => setShowInviteModal(false)}
                className="p-2 text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="grid grid-cols-5 gap-1.5">
              {(
                [
                  { id: 'APP_ID', label: 'App ID', icon: ShieldCheck },
                  { id: 'EMAIL', label: 'Email', icon: Mail },
                  { id: 'SMS', label: 'SMS', icon: MessageSquare },
                  { id: 'WHATSAPP', label: 'WhatsApp', icon: Share2 },
                  { id: 'IN_APP', label: 'In-App', icon: Bell },
                ] as const
              ).map((m) => {
                const Icon = m.icon;
                return (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => {
                      setInviteMethod(m.id);
                      setExternalSharePreview(null);
                    }}
                    className={`min-h-[48px] px-2 py-2 rounded-xl text-[11px] font-medium flex flex-col items-center justify-center gap-1 cursor-pointer ${
                      inviteMethod === m.id
                        ? 'bg-[#C6FF33] text-[#0B0D0F] font-semibold'
                        : 'bg-[#0B0D0F] border border-white/10 text-[#9CA3AF]'
                    }`}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    <span>{m.label}</span>
                  </button>
                );
              })}
            </div>

            {inviteMethod === 'APP_ID' && (
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Friend&apos;s Smart Expense Manager User ID *
                </label>
                <input
                  type="text"
                  required
                  value={inviteTargetInput}
                  onChange={(e) => setInviteTargetInput(e.target.value)}
                  placeholder="SEM-XXXXXXX"
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
            )}

            {inviteMethod === 'EMAIL' && (
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Registered Email Address *
                </label>
                <input
                  type="email"
                  required
                  value={inviteTargetInput}
                  onChange={(e) => setInviteTargetInput(e.target.value)}
                  placeholder="friend@example.com"
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
                {profile.email && (
                  <p className="text-[11px] text-[#9CA3AF] mt-1">
                    Signed-in sender email: {profile.email}
                  </p>
                )}
              </div>
            )}

            {inviteMethod === 'SMS' && (
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Phone Number (masked before storing; SMS opens only on confirmation) *
                </label>
                <input
                  type="tel"
                  required
                  value={inviteTargetInput}
                  onChange={(e) => setInviteTargetInput(e.target.value)}
                  placeholder="+91 98XXXXXX10"
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
            )}

            {(inviteMethod === 'WHATSAPP' || inviteMethod === 'IN_APP') && (
              <p className="text-xs text-[#9CA3AF]">
                Generates a verified group invitation token &amp; join code ({selectedGroup.joinCode}).
              </p>
            )}

            {(inviteMethod === 'EMAIL' || inviteMethod === 'SMS' || inviteMethod === 'WHATSAPP') && (
              <label className="flex items-start gap-2.5 p-3 rounded-xl bg-[#0B0D0F] border border-white/10 text-xs text-[#9CA3AF] cursor-pointer">
                <input
                  type="checkbox"
                  checked={contactPermissionConfirmed}
                  onChange={(e) => setContactPermissionConfirmed(e.target.checked)}
                  className="mt-0.5 accent-[#C6FF33]"
                />
                <span>
                  I grant permission to share this invitation details and understand messages are never sent automatically without my confirmation.
                </span>
              </label>
            )}

            {externalSharePreview && (
              <div className="p-3.5 rounded-xl bg-[#2CE59B]/10 border border-[#2CE59B]/35 space-y-2.5">
                <p className="text-xs text-[#2CE59B] font-semibold">
                  Invitation Ready — Confirm to Launch Share Sheet
                </p>
                <p className="text-[11px] text-[#F3F5F7] whitespace-pre-line">
                  {externalSharePreview.text}
                </p>
                <a
                  href={externalSharePreview.url}
                  target="_blank"
                  rel="noreferrer"
                  className="min-h-[42px] px-4 py-2 rounded-xl bg-[#2CE59B] text-[#0B0D0F] text-xs font-semibold inline-flex items-center gap-2"
                >
                  <ExternalLink className="w-4 h-4" />
                  <span>{externalSharePreview.channelLabel}</span>
                </a>
              </div>
            )}

            <button
              type="submit"
              className="w-full min-h-[46px] rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              Generate &amp; Send Invitation
            </button>
          </form>
        </div>
      )}

      {/* Add / Edit Group Expense Modal */}
      {expenseModalState.open && selectedGroupData && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
          <form
            onSubmit={handleSaveExpenseSubmit}
            className="w-full max-w-2xl bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4 my-8"
          >
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-[#F3F5F7]">
                {expenseModalState.editing ? 'Edit Group Expense' : 'Add Group Expense'}
              </h2>
              <div className="flex items-center gap-2">
                <input
                  ref={receiptFileRef}
                  type="file"
                  accept="image/*,application/pdf"
                  className="hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    if (f) handleGroupReceiptUpload(f);
                  }}
                />
                <button
                  type="button"
                  disabled={expOcrLoading}
                  onClick={() => receiptFileRef.current?.click()}
                  className="min-h-[38px] px-3 py-1.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#C6FF33] inline-flex items-center gap-1.5 cursor-pointer"
                >
                  <Receipt className="w-3.5 h-3.5" />
                  <span>{expOcrLoading ? 'Scanning Receipt...' : 'Upload Receipt OCR'}</span>
                </button>
                <button
                  type="button"
                  onClick={() => setExpenseModalState({ open: false, editing: null })}
                  className="p-2 text-[#9CA3AF] hover:text-white cursor-pointer"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Paid By *
                </label>
                <select
                  value={expPaidByUid}
                  onChange={(e) => setExpPaidByUid(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                >
                  {selectedGroupData.members.map((m) => (
                    <option key={m.uid} value={m.uid}>
                      {m.displayName} {m.uid === activeUid ? '(You)' : ''}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Amount ({currency}) *
                </label>
                <input
                  type="number"
                  step="0.01"
                  min="0.01"
                  required
                  value={expAmount}
                  onChange={(e) => setExpAmount(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Description *
                </label>
                <input
                  type="text"
                  required
                  value={expDesc}
                  onChange={(e) => setExpDesc(e.target.value)}
                  placeholder="Electricity Bill, Groceries, Dinner..."
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Category
                </label>
                <select
                  value={expCategory}
                  onChange={(e) => {
                    const nextCat = e.target.value as ExpenseCategory;
                    setExpCategory(nextCat);
                    if (nextCat !== 'Other') setExpCustomDetail('');
                  }}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                >
                  {EXPENSE_CATEGORIES.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
              </div>

              {expCategory === 'Other' && (
                <div className="sm:col-span-2 rounded-xl bg-[#0B0D0F] border border-[#C6FF33]/45 p-3.5 space-y-2">
                  <label className="block text-xs font-semibold text-[#C6FF33]">
                    Where have you spent this money? *
                  </label>
                  <input
                    type="text"
                    required
                    value={expCustomDetail}
                    onChange={(e) => setExpCustomDetail(e.target.value)}
                    placeholder="Enter expense details (e.g. Mobile recharge, Movie ticket, Gift...)"
                    className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#121519] border border-[#C6FF33]/35 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              )}

              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">Date *</label>
                <input
                  type="date"
                  required
                  value={expDate}
                  onChange={(e) => setExpDate(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">Time *</label>
                <input
                  type="time"
                  required
                  value={expTime}
                  onChange={(e) => setExpTime(e.target.value)}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
            </div>

            {/* Split Type Tabs */}
            <div className="space-y-3 pt-2 border-t border-white/10">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <label className="text-xs font-semibold text-[#F3F5F7]">Split Method</label>
                <div className="grid grid-cols-4 gap-1.5">
                  {(
                    [
                      { id: 'EQUAL', label: 'Equal' },
                      { id: 'CUSTOM_AMOUNT', label: 'Custom ₹' },
                      { id: 'PERCENTAGE', label: '%' },
                      { id: 'SHARES', label: 'Shares' },
                    ] as const
                  ).map((st) => (
                    <button
                      key={st.id}
                      type="button"
                      onClick={() => setExpSplitType(st.id)}
                      className={`min-h-[34px] px-3 rounded-lg text-xs font-medium cursor-pointer ${
                        expSplitType === st.id
                          ? 'bg-[#C6FF33] text-[#0B0D0F] font-semibold'
                          : 'bg-[#0B0D0F] border border-white/10 text-[#9CA3AF]'
                      }`}
                    >
                      {st.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-2">
                {selectedGroupData.members.map((m) => {
                  const included = expParticipants.includes(m.uid);
                  const previewShare = liveSplitPreview.shares[m.uid] ?? 0;
                  return (
                    <div
                      key={m.uid}
                      className="p-3 rounded-xl bg-[#0B0D0F] border border-white/10 flex items-center justify-between gap-3"
                    >
                      <label className="flex items-center gap-2.5 text-sm text-[#F3F5F7] cursor-pointer">
                        <input
                          type="checkbox"
                          checked={included}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setExpParticipants((prev) => Array.from(new Set([...prev, m.uid])));
                            } else {
                              setExpParticipants((prev) => prev.filter((id) => id !== m.uid));
                            }
                          }}
                          className="accent-[#C6FF33]"
                        />
                        <span>{m.displayName}</span>
                      </label>

                      <div className="flex items-center gap-2.5">
                        {included && expSplitType !== 'EQUAL' && (
                          <input
                            type="number"
                            step="0.01"
                            min="0"
                            value={expCustomInputs[m.uid] || ''}
                            onChange={(e) =>
                              setExpCustomInputs((prev) => ({ ...prev, [m.uid]: e.target.value }))
                            }
                            placeholder={
                              expSplitType === 'PERCENTAGE'
                                ? '%'
                                : expSplitType === 'SHARES'
                                  ? '1'
                                  : '0.00'
                            }
                            className="w-24 min-h-[36px] px-2.5 py-1 rounded-lg bg-[#121519] border border-white/15 text-xs font-mono-num text-[#F3F5F7] text-right focus:outline-none focus:border-[#C6FF33]"
                          />
                        )}
                        <span className="text-xs font-mono-num text-[#C6FF33] min-w-[72px] text-right">
                          {included ? formatMoney(previewShare, currency) : 'Excluded'}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {expFormError && <p className="text-xs text-[#EF4444]">{expFormError}</p>}

            <button
              type="submit"
              className="w-full min-h-[46px] rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              Save Group Expense
            </button>
          </form>
        </div>
      )}

      {/* SETTLE NOW Workflow Modal */}
      {settleModalDebt && activeModalSettlement && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
          <div className="w-full max-w-lg bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-5 my-8">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-[#F3F5F7]">Settle Balance</h2>
                <p className="text-xs text-[#9CA3AF]">
                  {activeModalSettlement.payerName} paying {activeModalSettlement.receiverName}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSettleModalDebt(null)}
                className="p-2 text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-4 rounded-xl bg-[#0B0D0F] border border-white/10 flex items-center justify-between">
              <div>
                <p className="text-xs text-[#9CA3AF]">Outstanding Amount</p>
                <p className="text-2xl font-bold font-mono-num text-[#C6FF33] mt-0.5">
                  {formatMoney(activeModalSettlement.amount, currency)}
                </p>
                <p className="text-xs font-mono-num text-[#9CA3AF] mt-1">
                  Receiver UPI: {activeModalSettlement.receiverUpiId || 'Not configured yet'}
                </p>
              </div>
              <div className="text-right">
                <p className="text-xs text-[#9CA3AF]">Status</p>
                <p className="text-xs font-semibold text-[#FF6B2C] mt-1">
                  {activeModalSettlement.status.replace('_', ' ')}
                </p>
              </div>
            </div>

            {!activeModalSettlement.receiverUpiId && (
              <div className="p-3 rounded-xl bg-[#FF6B2C]/10 border border-[#FF6B2C]/35 text-xs text-[#FDBA74] flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-[#FF6B2C]" />
                <span>
                  {activeModalSettlement.receiverName} has not added a verified UPI ID yet. You can still submit a manual reference/UTR once paid, or ask them to configure their UPI ID above.
                </span>
              </div>
            )}

            {/* Pay via UPI or Dynamic QR */}
            {activeModalSettlement.receiverUpiId && activeModalSettlement.status !== 'COMPLETED' && (
              <div className="space-y-2.5">
                <p className="text-xs font-semibold text-[#F3F5F7]">
                  Step 1: Pay via UPI App or Dynamic QR Code
                </p>
                <div className="grid grid-cols-2 gap-2.5">
                  <button
                    type="button"
                    onClick={() => {
                      const uri = buildUpiPaymentUri({
                        upiId: activeModalSettlement.receiverUpiId,
                        payeeName: activeModalSettlement.receiverName,
                        amount: activeModalSettlement.amount,
                        note: activeModalSettlement.note,
                        transactionRef: activeModalSettlement.id,
                      });
                      handleSettlementTransition(activeModalSettlement, 'INITIATE_UPI', {
                        appName: 'UPI App',
                        method: 'UPI',
                      });
                      window.location.href = uri;
                    }}
                    className="min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold inline-flex items-center justify-center gap-2 hover:bg-[#d4ff66] cursor-pointer"
                  >
                    <ExternalLink className="w-4 h-4" />
                    <span>Pay via UPI App</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      const uri = buildUpiPaymentUri({
                        upiId: activeModalSettlement.receiverUpiId,
                        payeeName: activeModalSettlement.receiverName,
                        amount: activeModalSettlement.amount,
                        note: activeModalSettlement.note,
                        transactionRef: activeModalSettlement.id,
                      });
                      setQrModalState({
                        open: true,
                        mode: 'DISPLAY',
                        upiUri: uri,
                        receiverName: activeModalSettlement.receiverName,
                        receiverUpiId: activeModalSettlement.receiverUpiId,
                        amount: activeModalSettlement.amount,
                        note: activeModalSettlement.note,
                        transactionRef: activeModalSettlement.id,
                        settlement: activeModalSettlement,
                      });
                    }}
                    className="min-h-[44px] px-4 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-semibold text-[#F3F5F7] inline-flex items-center justify-center gap-2 hover:border-[#C6FF33]/50 cursor-pointer"
                  >
                    <QrCode className="w-4 h-4 text-[#C6FF33]" />
                    <span>Show Dynamic QR</span>
                  </button>
                </div>
              </div>
            )}

            {/* Step 2: Explicit Payer Confirmation */}
            {activeModalSettlement.payerUid === activeUid &&
              activeModalSettlement.status !== 'COMPLETED' && (
                <div className="space-y-3 pt-3 border-t border-white/10">
                  <p className="text-xs font-semibold text-[#F3F5F7]">
                    Step 2: Payer Verification (enter UTR / UPI Ref ID after paying)
                  </p>
                  <div className="flex items-center gap-2">
                    <input
                      type="text"
                      value={settleUtrInput}
                      onChange={(e) => setSettleUtrInput(e.target.value)}
                      placeholder="Enter 12-digit UTR or Transaction Ref"
                      className="flex-1 min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                    />
                    <button
                      type="button"
                      onClick={() =>
                        handleSettlementTransition(activeModalSettlement, 'SUBMIT_PAYER_PROOF', {
                          utr: settleUtrInput,
                          method: activeModalSettlement.paymentMethod,
                        })
                      }
                      className="min-h-[44px] px-4 py-2 rounded-xl bg-[#2CE59B] text-[#0B0D0F] text-xs font-semibold whitespace-nowrap cursor-pointer"
                    >
                      Submit Proof
                    </button>
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <button
                      type="button"
                      onClick={() =>
                        handleSettlementTransition(activeModalSettlement, 'MARK_FAILED', {
                          utr: 'UPI timeout / cancelled',
                        })
                      }
                      className="text-xs text-[#FCA5A5] hover:underline cursor-pointer"
                    >
                      Payment Failed? Mark as Failed
                    </button>
                  </div>
                </div>
              )}

            {/* Step 3: Receiver Confirmation */}
            {activeModalSettlement.receiverUid === activeUid &&
              activeModalSettlement.status !== 'COMPLETED' && (
                <div className="space-y-3 pt-3 border-t border-white/10">
                  <p className="text-xs font-semibold text-[#2CE59B]">
                    Receiver Verification: Confirm only once funds appear in your bank account
                  </p>
                  <label className="flex items-center gap-2 text-xs text-[#9CA3AF] cursor-pointer">
                    <input
                      type="checkbox"
                      checked={logToPersonalLedger}
                      onChange={(e) => setLogToPersonalLedger(e.target.checked)}
                      className="accent-[#C6FF33]"
                    />
                    <span>Record settled income/payment in Personal Ledger upon confirmation</span>
                  </label>
                  <div className="flex items-center gap-2.5">
                    <button
                      type="button"
                      onClick={() =>
                        handleSettlementTransition(activeModalSettlement, 'RECEIVER_CONFIRM')
                      }
                      className="flex-1 min-h-[44px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold inline-flex items-center justify-center gap-1.5 cursor-pointer"
                    >
                      <Check className="w-4 h-4" />
                      <span>Confirm Settlement Received</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => handleSettlementTransition(activeModalSettlement, 'REJECT')}
                      className="min-h-[44px] px-4 py-2 rounded-xl bg-[#181C22] border border-[#EF4444]/40 text-xs text-[#FCA5A5] cursor-pointer"
                    >
                      Reject
                    </button>
                  </div>
                </div>
              )}
          </div>
        </div>
      )}

      {/* Dynamic QR Code / QR Scanner Modal */}
      {qrModalState.open && (
        <QrCodeModal
          mode={qrModalState.mode}
          upiUri={qrModalState.upiUri}
          receiverName={qrModalState.receiverName}
          receiverUpiId={qrModalState.receiverUpiId}
          amount={qrModalState.amount}
          currencySymbol={currency}
          note={qrModalState.note}
          transactionRef={qrModalState.transactionRef}
          onClose={() => setQrModalState({ open: false, mode: 'SCAN' })}
          onLaunchUpiApp={(appName) => {
            if (qrModalState.settlement) {
              handleSettlementTransition(qrModalState.settlement, 'INITIATE_UPI', {
                appName,
                method: 'QR',
              });
            }
          }}
        />
      )}
    </div>
  );
};
