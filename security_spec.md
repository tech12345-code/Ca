# Smart Expense Manager — Firestore Security Specification

## 1. Data Invariants
1. **Strict User Isolation**: Every document under `/users/{userId}` and its subcollections (`transactions`, `upiConnections`, `fcmTokens`, `alertHistory`) belongs strictly to `userId == request.auth.uid`. Cross-user reads, lists, creates, updates, or deletes are unconditionally denied.
2. **Verified Identity**: All write operations and PII profile accesses require `request.auth != null && request.auth.token.email_verified == true`.
3. **Positive Monetary Bounds**: Every transaction, group expense, and settlement amount must be a finite number strictly greater than `0` and `<= 100000000`. Opening balance and monthly budget must be `>= 0` and `<= 100000000`.
4. **Immutable Ownership & Creation Time**: `uid`, `id`, and `createdAt` are immutable after document creation. `createdAt` and `updatedAt` must equal `request.time` (Firestore server timestamp).
5. **Relational Integrity**: Subcollection documents cannot be created unless the parent `/users/{userId}` profile document exists. Group expense and settlement writes require active `/groupMembers/{groupId}_{uid}` membership.
6. **Zero Secret Storage**: No fields exist for passwords, UPI PINs, OTPs, or banking credentials anywhere in the schema.
7. **PII Separation & Group Privacy**: Emails are never written to `/userDirectory`. Phone numbers for SMS invitations are masked (`••••••1234`) before cloud storage. Group expenses and settlements are strictly restricted to users present in `memberUids`.
8. **Dual-Party Settlement Integrity**: Only the receiver (`receiverUid`) can mark a settlement `COMPLETED`, and already-`COMPLETED` settlements cannot be modified or re-confirmed.

## 2. Adversarial "Dirty Dozen" Payloads (Expected: PERMISSION_DENIED)
1. **Cross-User Profile Read**: User B attempts `get(/users/userA)` -> Denied (`userId != request.auth.uid`).
2. **Identity Spoofing on Transaction Create**: User A attempts `create` under `/users/userA/transactions/tx1` with `uid: "userB"` -> Denied (`data.uid == userId`).
3. **Ghost Field Injection (Shadow Update)**: User A sends valid transaction fields plus `isAdmin: true` -> Denied (`keys().hasOnly(...)`).
4. **Negative or Zero Expense Amount**: User A creates transaction or group expense with `amount: -500` or `amount: 0` -> Denied (`amount > 0`).
5. **Category Enum Bypass**: User A creates transaction with `category: "CryptoHack"` -> Denied (category enum check).
6. **ID Poisoning (1KB Path Variable)**: User A creates document with `transactionId` of length 300 -> Denied (`isValidId(transactionId)`).
7. **Client Timestamp Forgery**: User A creates transaction or group document with client-supplied `createdAt` instead of `serverTimestamp()` -> Denied (`createdAt == request.time`).
8. **Immutable Field Mutation**: User A updates transaction changing `uid` or `createdAt` -> Denied (`affectedKeys().hasOnly(...)` + immutability check).
9. **Unverified Email Write**: User with `email_verified: false` attempts write -> Denied.
10. **Non-Member Group Expense Snooping**: User C (not in `memberUids`) queries `/groupExpenses` for Group A -> Denied (`request.auth.uid in resource.data.memberUids`).
11. **Payer Self-Confirmation Fraud**: Payer attempts to update settlement `status` to `'COMPLETED'` -> Denied (only `receiverUid` may set `COMPLETED`).
12. **Duplicate Settlement Confirmation**: Actor attempts to update an already `'COMPLETED'` settlement -> Denied (`existing().status != 'COMPLETED'`).
