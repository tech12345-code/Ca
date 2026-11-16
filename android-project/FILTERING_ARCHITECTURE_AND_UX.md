# Advanced Transaction Filtering — Architecture & UI/UX Guide

## 1. Room Database Query Optimization
- **Composite SQLite Indexes** (`Entities.kt`):
  - `(uid, timestampMillis)` for chronological sorting and date-range scans.
  - `(uid, syncStatus, timestampMillis)` to exclude `PENDING_DELETE` rows without full table scans.
  - `(uid, category, timestampMillis)` and `(uid, paymentMethod, timestampMillis)` for multi-filter queries.
- **Parameterized Reactive `@RawQuery`** (`Daos.kt`, `ExpenseRepository.kt`):
  - `observeFilteredTransactions(query: SupportSQLiteQuery)` dynamically builds parameterized `WHERE` clauses (`AND`, `IN (?, ...)`, `timestampMillis BETWEEN`, `LIKE ? COLLATE NOCASE`) while retaining Room's reactive `Flow` invalidation on table updates.
  - Input is debounced (`120ms` in `MainViewModel`) to avoid redundant SQLite queries while typing search terms.
- **Chronological Running Balance Integrity**:
  - `ExpenseRepository.observeFilteredLedger` computes global chronological running balances from the full user ledger first, then maps each filtered SQLite row to its true historical `runningBalanceAfter`. Filtering out categories never falsifies historical account balances.

## 2. Cloud Firestore Query Pushdown & Indexing
- **Security Rule Alignment**: Every query on `/users/{uid}/transactions` includes `.whereEqualTo("uid", uid)`, matching `firestore.rules` (`resource.data.uid == request.auth.uid`).
- **Selective Server-Side Pushdown** (`FirestoreSyncService.queryFilteredTransactions` & `src/lib/storageSync.ts`):
  - Pushes `uid`, optional `type`, `timestampMillis` range bounds (`>=` / `<=`), and the most selective `whereIn` / `whereEqualTo` dimension (`category` or `paymentMethod`) directly to Firestore composite indexes (`firestore.indexes.json`).
  - Handles Firestore's single-`whereIn` and no-substring-`LIKE` constraints gracefully by intersecting the secondary dimension and case-insensitive description/merchant/note/referenceId matching on the narrowed snapshot.
  - Uses Firestore offline persistent cache (`PersistentCacheSettings` on Android, `persistentLocalCache` on Web) so combined queries remain instant offline.

## 3. UI/UX Considerations for Filter Management & Clearing
1. **Progressive Disclosure**: Keep the primary search bar and `Filters (N)` button immediately visible while collapsing multi-select panels (Date Range, Categories, Payment Methods, Type) into an expandable container so the ledger remains uncluttered.
2. **Three-Tier Clearing Model**:
   - **Inline Search Clear (`×`)**: Clears description text without resetting categories/dates.
   - **Section-Level Reset & Removable Active Chips**: Each individual active criterion (e.g., `Food ×`, `Google Pay (UPI) ×`, `2026-11-01 → 2026-11-15 ×`) can be removed in one tap directly above the ledger without reopening the filter panel.
   - **Global `Clear All` & Empty-State Recovery**: Whenever `activeCount > 0`, a prominent `Clear All` action is visible, and a zero-match ledger displays a dedicated `"Reset All Filters"` button.
3. **Date Range Guardrails**: Selecting a start date later than the end date automatically normalizes the range so users never get stuck in an impossible empty state.
4. **Live Filtered Totals**: Display matching entry count, filtered expenses, and filtered income above the list so users understand the aggregate impact of their combined filters in real time.
