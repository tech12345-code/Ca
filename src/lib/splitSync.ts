import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  limit,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';
import { db, handleFirestoreError, OperationType } from './firebase';
import {
  generateAppUserCode,
  generateGroupJoinCode,
  reconcileOfflineExpenseSync,
} from './splitEngine';
import {
  Group,
  GroupExpense,
  GroupInvitation,
  GroupMember,
  GroupType,
  InviteMethod,
  PaymentRequest,
  Settlement,
  UserDirectoryProfile,
} from '../types/expense';

export interface SplitWorkspaceLocalState {
  groups: Group[];
  membersByGroup: Record<string, GroupMember[]>;
  invitations: GroupInvitation[];
  expenses: GroupExpense[];
  settlements: Settlement[];
  paymentRequests: PaymentRequest[];
}

const EMPTY_SPLIT_STATE: SplitWorkspaceLocalState = {
  groups: [],
  membersByGroup: {},
  invitations: [],
  expenses: [],
  settlements: [],
  paymentRequests: [],
};

const splitStorageKey = (uid: string) => `sem_v1_${uid}_splits`;

export function loadLocalSplitState(uid: string): SplitWorkspaceLocalState {
  try {
    const raw = localStorage.getItem(splitStorageKey(uid));
    if (!raw) return EMPTY_SPLIT_STATE;
    const parsed = JSON.parse(raw) as Partial<SplitWorkspaceLocalState>;
    return {
      groups: parsed.groups || [],
      membersByGroup: parsed.membersByGroup || {},
      invitations: parsed.invitations || [],
      expenses: parsed.expenses || [],
      settlements: parsed.settlements || [],
      paymentRequests: parsed.paymentRequests || [],
    };
  } catch {
    return EMPTY_SPLIT_STATE;
  }
}

export function saveLocalSplitState(uid: string, state: SplitWorkspaceLocalState): void {
  localStorage.setItem(splitStorageKey(uid), JSON.stringify(state));
}

export async function syncUserDirectoryProfile(profile: {
  uid: string;
  displayName: string;
  appUserCode: string;
  upiId: string;
  upiVerified: boolean;
}): Promise<void> {
  const path = `userDirectory/${profile.uid}`;
  try {
    await setDoc(doc(db, 'userDirectory', profile.uid), {
      uid: profile.uid,
      appUserCode: profile.appUserCode,
      displayName: profile.displayName.slice(0, 120) || 'User',
      upiId: profile.upiId.slice(0, 100),
      upiVerified: Boolean(profile.upiVerified),
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, path);
  }
}

export async function lookupUserDirectoryByAppCode(
  codeOrUid: string
): Promise<UserDirectoryProfile | null> {
  const cleaned = codeOrUid.trim();
  if (!cleaned) return null;

  // Check direct UID lookup first if it doesn't look like SEM-
  if (!cleaned.toUpperCase().startsWith('SEM-')) {
    try {
      const snap = await getDoc(doc(db, 'userDirectory', cleaned));
      if (snap.exists()) {
        const d = snap.data();
        return {
          uid: String(d.uid || snap.id),
          appUserCode: String(d.appUserCode || generateAppUserCode(snap.id)),
          displayName: String(d.displayName || 'User'),
          upiId: String(d.upiId || ''),
          upiVerified: Boolean(d.upiVerified),
          updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
        };
      }
    } catch {
      // Fall through to appUserCode query
    }
  }

  const path = 'userDirectory';
  try {
    const q = query(
      collection(db, 'userDirectory'),
      where('appUserCode', '==', cleaned.toUpperCase()),
      limit(5)
    );
    const snap = await getDocs(q);
    if (snap.empty) return null;
    const d = snap.docs[0].data();
    return {
      uid: String(d.uid || snap.docs[0].id),
      appUserCode: String(d.appUserCode || ''),
      displayName: String(d.displayName || 'User'),
      upiId: String(d.upiId || ''),
      upiVerified: Boolean(d.upiVerified),
      updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
    };
  } catch (err) {
    handleFirestoreError(err, OperationType.LIST, path);
  }
}

export async function createGroupInFirestore(params: {
  name: string;
  groupType: GroupType;
  iconKey: string;
  settlementDueDate: string;
  creatorUid: string;
  creatorName: string;
  creatorUpiId: string;
  creatorUpiVerified: boolean;
}): Promise<{ group: Group; member: GroupMember }> {
  const now = Date.now();
  const groupId = `grp_${now}_${Math.random().toString(36).slice(2, 7)}`;
  const joinCode = generateGroupJoinCode(groupId);
  const appUserCode = generateAppUserCode(params.creatorUid);

  const group: Group = {
    id: groupId,
    name: params.name.trim().slice(0, 100),
    groupType: params.groupType,
    iconKey: params.iconKey || 'users',
    joinCode,
    creatorUid: params.creatorUid,
    creatorName: params.creatorName.trim().slice(0, 120) || 'Account Holder',
    memberUids: [params.creatorUid],
    settlementDueDate: params.settlementDueDate || '',
    createdAtMillis: now,
    updatedAtMillis: now,
  };

  const member: GroupMember = {
    id: `${groupId}_${params.creatorUid}`,
    groupId,
    uid: params.creatorUid,
    displayName: group.creatorName,
    appUserCode,
    role: 'ADMIN',
    status: 'ACTIVE',
    upiId: params.creatorUpiId,
    upiVerified: params.creatorUpiVerified,
    joinedAtMillis: now,
    updatedAtMillis: now,
  };

  const batch = writeBatch(db);
  batch.set(doc(db, 'groups', groupId), {
    id: group.id,
    name: group.name,
    groupType: group.groupType,
    iconKey: group.iconKey,
    joinCode: group.joinCode,
    creatorUid: group.creatorUid,
    creatorName: group.creatorName,
    memberUids: group.memberUids,
    settlementDueDate: group.settlementDueDate,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(doc(db, 'groupMembers', member.id), {
    id: member.id,
    groupId: member.groupId,
    uid: member.uid,
    memberUids: group.memberUids,
    displayName: member.displayName,
    appUserCode: member.appUserCode,
    role: member.role,
    status: member.status,
    upiId: member.upiId,
    upiVerified: member.upiVerified,
    joinedAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(doc(db, 'groupJoinCodes', joinCode), {
    joinCode,
    groupId: group.id,
    groupName: group.name,
    groupType: group.groupType,
    creatorUid: group.creatorUid,
    creatorName: group.creatorName,
    active: true,
    updatedAt: serverTimestamp(),
  });

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `groups/${groupId}`);
  }

  return { group, member };
}

export async function joinGroupByCodeInFirestore(params: {
  joinCode: string;
  uid: string;
  displayName: string;
  upiId: string;
  upiVerified: boolean;
}): Promise<{ groupId: string; groupName: string }> {
  const code = params.joinCode.trim().toUpperCase();
  const codePath = `groupJoinCodes/${code}`;
  try {
    const codeSnap = await getDoc(doc(db, 'groupJoinCodes', code));
    if (!codeSnap.exists()) {
      throw new Error('No active group found for that Join Code.');
    }
    const meta = codeSnap.data();
    const groupId = String(meta.groupId);
    const groupRef = doc(db, 'groups', groupId);
    const groupSnap = await getDoc(groupRef);

    let nextMemberUids: string[] = [params.uid];
    if (groupSnap.exists()) {
      const existingUids = (groupSnap.data().memberUids as string[]) || [];
      nextMemberUids = Array.from(new Set([...existingUids, params.uid]));
      if (!existingUids.includes(params.uid)) {
        await updateDoc(groupRef, {
          memberUids: nextMemberUids,
          updatedAt: serverTimestamp(),
        });
      }
    }

    const memberDocId = `${groupId}_${params.uid}`;
    await setDoc(doc(db, 'groupMembers', memberDocId), {
      id: memberDocId,
      groupId,
      uid: params.uid,
      memberUids: nextMemberUids,
      displayName: params.displayName.slice(0, 120) || 'Member',
      appUserCode: generateAppUserCode(params.uid),
      role: 'MEMBER',
      status: 'ACTIVE',
      upiId: params.upiId,
      upiVerified: params.upiVerified,
      joinedAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });

    return { groupId, groupName: String(meta.groupName || 'Group') };
  } catch (err) {
    if (err instanceof Error && err.message.includes('No active group')) throw err;
    handleFirestoreError(err, OperationType.WRITE, codePath);
  }
}

export async function createInvitationInFirestore(inv: GroupInvitation): Promise<void> {
  const path = `invitations/${inv.id}`;
  try {
    await setDoc(doc(db, 'invitations', inv.id), {
      id: inv.id,
      groupId: inv.groupId,
      groupName: inv.groupName.slice(0, 100),
      groupType: inv.groupType,
      inviterUid: inv.inviterUid,
      inviterName: inv.inviterName.slice(0, 120),
      inviteMethod: inv.inviteMethod,
      targetUid: inv.targetUid.slice(0, 128),
      targetAppCode: inv.targetAppCode.slice(0, 32),
      targetEmail: inv.targetEmail.trim().toLowerCase().slice(0, 200),
      targetPhoneMasked: inv.targetPhoneMasked.slice(0, 32),
      inviteToken: inv.inviteToken.slice(0, 64),
      status: 'PENDING',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, path);
  }
}

export async function respondInvitationInFirestore(params: {
  invitation: GroupInvitation;
  decision: 'ACCEPT' | 'REJECT';
  responderUid: string;
  responderName: string;
  responderUpiId: string;
  responderUpiVerified: boolean;
}): Promise<void> {
  const { invitation, decision, responderUid } = params;
  const invPath = `invitations/${invitation.id}`;
  try {
    await updateDoc(doc(db, 'invitations', invitation.id), {
      status: decision === 'ACCEPT' ? 'ACCEPTED' : 'REJECTED',
      targetUid: responderUid,
      updatedAt: serverTimestamp(),
    });

    if (decision === 'ACCEPT') {
      const groupRef = doc(db, 'groups', invitation.groupId);
      let nextMemberUids = [invitation.inviterUid, responderUid];
      try {
        const gSnap = await getDoc(groupRef);
        if (gSnap.exists()) {
          const currentUids = (gSnap.data().memberUids as string[]) || [];
          nextMemberUids = Array.from(new Set([...currentUids, responderUid]));
          if (!currentUids.includes(responderUid)) {
            await updateDoc(groupRef, {
              memberUids: nextMemberUids,
              updatedAt: serverTimestamp(),
            });
          }
        }
      } catch {
        // Fallback join when non-member updates group membership
        await updateDoc(groupRef, {
          memberUids: nextMemberUids,
          updatedAt: serverTimestamp(),
        });
      }

      const memberDocId = `${invitation.groupId}_${responderUid}`;
      await setDoc(doc(db, 'groupMembers', memberDocId), {
        id: memberDocId,
        groupId: invitation.groupId,
        uid: responderUid,
        memberUids: nextMemberUids,
        displayName: params.responderName.slice(0, 120) || 'Member',
        appUserCode: generateAppUserCode(responderUid),
        role: 'MEMBER',
        status: 'ACTIVE',
        upiId: params.responderUpiId,
        upiVerified: params.responderUpiVerified,
        joinedAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, invPath);
  }
}

export async function syncGroupExpenseToFirestore(
  expense: GroupExpense,
  isNew: boolean
): Promise<void> {
  const path = `groupExpenses/${expense.id}`;
  const ref = doc(db, 'groupExpenses', expense.id);
  try {
    const payload = {
      id: expense.id,
      groupId: expense.groupId,
      memberUids: expense.memberUids,
      paidByUid: expense.paidByUid,
      paidByName: expense.paidByName.slice(0, 120),
      amount: Number(expense.amount),
      description: expense.description.slice(0, 200),
      category: expense.category,
      customCategoryDetail: (expense.customCategoryDetail || '').slice(0, 120),
      transactionDate: expense.transactionDate,
      transactionTime: expense.transactionTime,
      timestampMillis: Math.floor(expense.timestampMillis),
      splitType: expense.splitType,
      participantUids: expense.participantUids,
      shares: expense.shares,
      receiptReference: (expense.receiptReference || '').slice(0, 120),
      note: (expense.note || '').slice(0, 500),
      updatedAt: serverTimestamp(),
    };
    if (isNew) {
      await setDoc(ref, { ...payload, createdAt: serverTimestamp() });
    } else {
      const snap = await getDoc(ref);
      if (!snap.exists()) {
        await setDoc(ref, { ...payload, createdAt: serverTimestamp() });
      } else {
        await updateDoc(ref, payload);
      }
    }
  } catch (err) {
    handleFirestoreError(err, isNew ? OperationType.CREATE : OperationType.UPDATE, path);
  }
}

export async function deleteGroupExpenseInFirestore(expenseId: string): Promise<void> {
  const path = `groupExpenses/${expenseId}`;
  try {
    await deleteDoc(doc(db, 'groupExpenses', expenseId));
  } catch (err) {
    handleFirestoreError(err, OperationType.DELETE, path);
  }
}

export async function createSettlementAndRequestInFirestore(
  settlement: Settlement,
  paymentRequest: PaymentRequest
): Promise<void> {
  const path = `settlements/${settlement.id}`;
  const batch = writeBatch(db);
  batch.set(doc(db, 'settlements', settlement.id), {
    id: settlement.id,
    groupId: settlement.groupId,
    groupName: settlement.groupName.slice(0, 100),
    memberUids: settlement.memberUids,
    payerUid: settlement.payerUid,
    payerName: settlement.payerName.slice(0, 120),
    receiverUid: settlement.receiverUid,
    receiverName: settlement.receiverName.slice(0, 120),
    receiverUpiId: settlement.receiverUpiId.slice(0, 100),
    amount: Number(settlement.amount),
    dueDate: settlement.dueDate.slice(0, 10),
    reminderEnabled: Boolean(settlement.reminderEnabled),
    status: settlement.status,
    paymentMethod: settlement.paymentMethod,
    paymentApp: (settlement.paymentApp || '').slice(0, 64),
    paymentReference: settlement.paymentReference.slice(0, 120),
    idempotencyKey: settlement.idempotencyKey,
    payerConfirmedAtMillis: settlement.payerConfirmedAtMillis,
    receiverConfirmedAtMillis: settlement.receiverConfirmedAtMillis,
    note: settlement.note.slice(0, 300),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(doc(db, 'paymentRequests', paymentRequest.id), {
    id: paymentRequest.id,
    groupId: paymentRequest.groupId,
    settlementId: paymentRequest.settlementId,
    memberUids: paymentRequest.memberUids,
    requesterUid: paymentRequest.requesterUid,
    requesterName: paymentRequest.requesterName.slice(0, 120),
    requesterUpiId: paymentRequest.requesterUpiId.slice(0, 100),
    payerUid: paymentRequest.payerUid,
    payerName: paymentRequest.payerName.slice(0, 120),
    amount: Number(paymentRequest.amount),
    description: paymentRequest.description.slice(0, 200),
    upiUri: paymentRequest.upiUri.slice(0, 500),
    transactionRef: paymentRequest.transactionRef.slice(0, 64),
    dueDate: paymentRequest.dueDate.slice(0, 10),
    status: paymentRequest.status,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, path);
  }
}

export async function updateSettlementStatusInFirestore(
  settlement: Settlement,
  paymentRequestId?: string
): Promise<void> {
  const path = `settlements/${settlement.id}`;
  try {
    await updateDoc(doc(db, 'settlements', settlement.id), {
      status: settlement.status,
      paymentMethod: settlement.paymentMethod,
      paymentApp: (settlement.paymentApp || '').slice(0, 64),
      paymentReference: settlement.paymentReference.slice(0, 120),
      payerConfirmedAtMillis: settlement.payerConfirmedAtMillis,
      receiverConfirmedAtMillis: settlement.receiverConfirmedAtMillis,
      note: settlement.note.slice(0, 300),
      updatedAt: serverTimestamp(),
    });
    if (paymentRequestId) {
      await updateDoc(doc(db, 'paymentRequests', paymentRequestId), {
        status: settlement.status,
        updatedAt: serverTimestamp(),
      });
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, path);
  }
}

export async function leaveGroupInFirestore(group: Group, memberUid: string): Promise<void> {
  const path = `groups/${group.id}`;
  try {
    const nextUids = group.memberUids.filter((u) => u !== memberUid);
    await updateDoc(doc(db, 'groups', group.id), {
      memberUids: nextUids,
      updatedAt: serverTimestamp(),
    });
    await updateDoc(doc(db, 'groupMembers', `${group.id}_${memberUid}`), {
      status: 'LEFT',
      memberUids: nextUids,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, path);
  }
}

export async function restoreSplitWorkspaceFromFirestore(
  uid: string,
  verifiedEmail: string,
  localState: SplitWorkspaceLocalState
): Promise<SplitWorkspaceLocalState> {
  const path = 'groups';
  try {
    const [groupsSnap, membersSnap, expensesSnap, settlementsSnap, reqsSnap, invTargetSnap, invSentSnap] =
      await Promise.all([
        getDocs(query(collection(db, 'groups'), where('memberUids', 'array-contains', uid))),
        getDocs(query(collection(db, 'groupMembers'), where('memberUids', 'array-contains', uid))),
        getDocs(query(collection(db, 'groupExpenses'), where('memberUids', 'array-contains', uid))),
        getDocs(query(collection(db, 'settlements'), where('memberUids', 'array-contains', uid))),
        getDocs(query(collection(db, 'paymentRequests'), where('memberUids', 'array-contains', uid))),
        getDocs(query(collection(db, 'invitations'), where('targetUid', '==', uid))),
        getDocs(query(collection(db, 'invitations'), where('inviterUid', '==', uid))),
      ]);

    const emailInvDocs = verifiedEmail
      ? (
          await getDocs(
            query(
              collection(db, 'invitations'),
              where('targetEmail', '==', verifiedEmail.trim().toLowerCase())
            )
          )
        ).docs
      : [];

    const groups: Group[] = groupsSnap.docs.map((dSnap) => {
      const d = dSnap.data();
      return {
        id: String(d.id || dSnap.id),
        name: String(d.name || ''),
        groupType: d.groupType || 'Roommates',
        iconKey: String(d.iconKey || 'users'),
        joinCode: String(d.joinCode || ''),
        creatorUid: String(d.creatorUid || ''),
        creatorName: String(d.creatorName || ''),
        memberUids: Array.isArray(d.memberUids) ? d.memberUids : [],
        settlementDueDate: String(d.settlementDueDate || ''),
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
      };
    });

    const membersByGroup: Record<string, GroupMember[]> = {};
    for (const mSnap of membersSnap.docs) {
      const d = mSnap.data();
      const gm: GroupMember = {
        id: String(d.id || mSnap.id),
        groupId: String(d.groupId || ''),
        uid: String(d.uid || ''),
        displayName: String(d.displayName || 'Member'),
        appUserCode: String(d.appUserCode || generateAppUserCode(String(d.uid || ''))),
        role: d.role === 'ADMIN' ? 'ADMIN' : 'MEMBER',
        status: d.status === 'LEFT' ? 'LEFT' : 'ACTIVE',
        upiId: String(d.upiId || ''),
        upiVerified: Boolean(d.upiVerified),
        joinedAtMillis: d.joinedAt?.toDate ? d.joinedAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
      };
      if (!membersByGroup[gm.groupId]) membersByGroup[gm.groupId] = [];
      membersByGroup[gm.groupId].push(gm);
    }

    const cloudExpenses: GroupExpense[] = expensesSnap.docs.map((eSnap) => {
      const d = eSnap.data();
      return {
        id: String(d.id || eSnap.id),
        groupId: String(d.groupId || ''),
        memberUids: Array.isArray(d.memberUids) ? d.memberUids : [],
        paidByUid: String(d.paidByUid || ''),
        paidByName: String(d.paidByName || ''),
        amount: Number(d.amount || 0),
        description: String(d.description || ''),
        category: d.category || 'Other',
        customCategoryDetail: String(d.customCategoryDetail || ''),
        transactionDate: String(d.transactionDate || ''),
        transactionTime: String(d.transactionTime || '12:00'),
        timestampMillis: Number(d.timestampMillis || Date.now()),
        splitType: d.splitType || 'EQUAL',
        participantUids: Array.isArray(d.participantUids) ? d.participantUids : [],
        shares: (d.shares as Record<string, number>) || {},
        receiptReference: String(d.receiptReference || ''),
        note: String(d.note || ''),
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
        pendingSync: false,
      };
    });

    // Offline-first reconciliation: push offline queued creates/updates/deletes
    const reconciled = reconcileOfflineExpenseSync(localState.expenses, cloudExpenses);
    for (const p of reconciled.pendingToPush) {
      await syncGroupExpenseToFirestore(p, p.syncAction === 'CREATE').catch(() => {});
    }
    for (const delId of reconciled.pendingToDeleteIds) {
      await deleteGroupExpenseInFirestore(delId).catch(() => {});
    }

    const settlements: Settlement[] = settlementsSnap.docs.map((sSnap) => {
      const d = sSnap.data();
      return {
        id: String(d.id || sSnap.id),
        groupId: String(d.groupId || ''),
        groupName: String(d.groupName || ''),
        memberUids: Array.isArray(d.memberUids) ? d.memberUids : [],
        payerUid: String(d.payerUid || ''),
        payerName: String(d.payerName || ''),
        receiverUid: String(d.receiverUid || ''),
        receiverName: String(d.receiverName || ''),
        receiverUpiId: String(d.receiverUpiId || ''),
        amount: Number(d.amount || 0),
        dueDate: String(d.dueDate || ''),
        reminderEnabled: Boolean(d.reminderEnabled),
        status: d.status || 'PENDING',
        paymentMethod: d.paymentMethod || 'UPI',
        paymentApp: String(d.paymentApp || ''),
        paymentReference: String(d.paymentReference || ''),
        idempotencyKey: String(d.idempotencyKey || ''),
        payerConfirmedAtMillis: d.payerConfirmedAtMillis ?? null,
        receiverConfirmedAtMillis: d.receiverConfirmedAtMillis ?? null,
        note: String(d.note || ''),
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
      };
    });

    const paymentRequests: PaymentRequest[] = reqsSnap.docs.map((rSnap) => {
      const d = rSnap.data();
      return {
        id: String(d.id || rSnap.id),
        groupId: String(d.groupId || ''),
        settlementId: String(d.settlementId || ''),
        memberUids: Array.isArray(d.memberUids) ? d.memberUids : [],
        requesterUid: String(d.requesterUid || ''),
        requesterName: String(d.requesterName || ''),
        requesterUpiId: String(d.requesterUpiId || ''),
        payerUid: String(d.payerUid || ''),
        payerName: String(d.payerName || ''),
        amount: Number(d.amount || 0),
        description: String(d.description || ''),
        upiUri: String(d.upiUri || ''),
        transactionRef: String(d.transactionRef || ''),
        dueDate: String(d.dueDate || ''),
        status: d.status || 'PENDING',
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
      };
    });

    const invMap = new Map<string, GroupInvitation>();
    for (const iSnap of [...invTargetSnap.docs, ...invSentSnap.docs, ...emailInvDocs]) {
      const d = iSnap.data();
      invMap.set(iSnap.id, {
        id: String(d.id || iSnap.id),
        groupId: String(d.groupId || ''),
        groupName: String(d.groupName || ''),
        groupType: d.groupType || 'Roommates',
        inviterUid: String(d.inviterUid || ''),
        inviterName: String(d.inviterName || ''),
        inviteMethod: d.inviteMethod || 'IN_APP',
        targetUid: String(d.targetUid || ''),
        targetAppCode: String(d.targetAppCode || ''),
        targetEmail: String(d.targetEmail || ''),
        targetPhoneMasked: String(d.targetPhoneMasked || ''),
        inviteToken: String(d.inviteToken || ''),
        status: d.status || 'PENDING',
        createdAtMillis: d.createdAt?.toDate ? d.createdAt.toDate().getTime() : Date.now(),
        updatedAtMillis: d.updatedAt?.toDate ? d.updatedAt.toDate().getTime() : Date.now(),
      });
    }

    return {
      groups,
      membersByGroup,
      invitations: Array.from(invMap.values()),
      expenses: reconciled.mergedExpenses.map((e) => ({ ...e, pendingSync: false })),
      settlements,
      paymentRequests,
    };
  } catch (err) {
    handleFirestoreError(err, OperationType.LIST, path);
  }
}
