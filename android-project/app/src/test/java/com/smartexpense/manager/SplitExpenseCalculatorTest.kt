package com.smartexpense.manager

import com.smartexpense.manager.domain.calculator.SettlementAction
import com.smartexpense.manager.domain.calculator.SplitExpenseCalculator
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberRole
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.GroupType
import com.smartexpense.manager.domain.model.InvitationStatus
import com.smartexpense.manager.domain.model.InviteMethod
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementDueCategory
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SplitType
import com.smartexpense.manager.domain.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitExpenseCalculatorTest {

    private val calc = SplitExpenseCalculator()

    private fun member(uid: String, name: String, role: GroupMemberRole = GroupMemberRole.MEMBER) =
        GroupMember(
            id = "grp1_$uid",
            groupId = "grp1",
            uid = uid,
            displayName = name,
            appUserCode = calc.generateAppUserCode(uid),
            role = role,
            status = GroupMemberStatus.ACTIVE,
            upiId = "${name.lowercase()}@okaxis",
            upiVerified = true,
            joinedAtMillis = 1000L,
            updatedAtMillis = 1000L
        )

    private fun group() = SplitGroup(
        id = "grp1",
        name = "Flat 402",
        groupType = GroupType.ROOMMATES,
        iconKey = "roommates",
        joinCode = "GRP-TEST1",
        creatorUid = "u1",
        creatorName = "Aarav",
        memberUids = listOf("u1", "u2", "u3"),
        settlementDueDate = "2026-11-20",
        createdAtMillis = 1000L,
        updatedAtMillis = 1000L
    )

    @Test
    fun equalSplit_conservesTotalWithRoundingRemainder() {
        val res = calc.calculateSplitShares(100.0, SplitType.EQUAL, listOf("u1", "u2", "u3"))
        assertTrue(res.isSuccess)
        val shares = res.getOrThrow()
        assertEquals(33.34, shares["u1"]!!, 0.001)
        assertEquals(33.33, shares["u2"]!!, 0.001)
        assertEquals(33.33, shares["u3"]!!, 0.001)
        assertEquals(100.0, shares.values.sum(), 0.001)
    }

    @Test
    fun customAmountSplit_enforcesExactTotal() {
        val invalid = calc.calculateSplitShares(
            300.0,
            SplitType.CUSTOM_AMOUNT,
            listOf("u1", "u2"),
            mapOf("u1" to 100.0, "u2" to 150.0)
        )
        assertTrue(invalid.isFailure)

        val valid = calc.calculateSplitShares(
            300.0,
            SplitType.CUSTOM_AMOUNT,
            listOf("u1", "u2"),
            mapOf("u1" to 120.0, "u2" to 180.0)
        )
        assertTrue(valid.isSuccess)
        assertEquals(120.0, valid.getOrThrow()["u1"]!!, 0.001)
    }

    @Test
    fun percentageSplit_validates100PercentAndAllocates() {
        val res = calc.calculateSplitShares(
            1000.0,
            SplitType.PERCENTAGE,
            listOf("u1", "u2", "u3"),
            mapOf("u1" to 50.0, "u2" to 30.0, "u3" to 20.0)
        )
        assertTrue(res.isSuccess)
        assertEquals(500.0, res.getOrThrow()["u1"]!!, 0.001)
        assertEquals(300.0, res.getOrThrow()["u2"]!!, 0.001)
        assertEquals(200.0, res.getOrThrow()["u3"]!!, 0.001)
    }

    @Test
    fun shareWeightsSplit_computesProportionalShares() {
        val res = calc.calculateSplitShares(
            400.0,
            SplitType.SHARES,
            listOf("u1", "u2"),
            mapOf("u1" to 3.0, "u2" to 1.0)
        )
        assertTrue(res.isSuccess)
        assertEquals(300.0, res.getOrThrow()["u1"]!!, 0.001)
        assertEquals(100.0, res.getOrThrow()["u2"]!!, 0.001)
    }

    @Test
    fun computeBalances_simplifiesDebtsAndAppliesCompletedSettlements() {
        val members = listOf(member("u1", "Aarav", GroupMemberRole.ADMIN), member("u2", "Rohan"), member("u3", "Meera"))
        val exp = GroupExpense(
            id = "e1",
            groupId = "grp1",
            memberUids = listOf("u1", "u2", "u3"),
            paidByUid = "u1",
            paidByName = "Aarav",
            amount = 900.0,
            description = "Electricity",
            category = ExpenseCategory.Bills,
            transactionDate = "2026-11-10",
            transactionTime = "10:00",
            timestampMillis = 1000L,
            splitType = SplitType.EQUAL,
            participantUids = listOf("u1", "u2", "u3"),
            shares = mapOf("u1" to 300.0, "u2" to 300.0, "u3" to 300.0),
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        val initial = calc.computeBalances(members, listOf(exp), emptyList())
        assertEquals(900.0, initial.totalGroupExpenses, 0.001)
        assertEquals(2, initial.simplifiedDebts.size)
        assertEquals(600.0, initial.memberBalances.first { it.uid == "u1" }.netBalance, 0.001)

        val completedSettlement = Settlement(
            id = "s1",
            groupId = "grp1",
            groupName = "Flat 402",
            memberUids = listOf("u1", "u2", "u3"),
            payerUid = "u2",
            payerName = "Rohan",
            receiverUid = "u1",
            receiverName = "Aarav",
            receiverUpiId = "aarav@okaxis",
            amount = 300.0,
            status = SettlementStatus.COMPLETED,
            idempotencyKey = "k1",
            createdAtMillis = 2000L,
            updatedAtMillis = 2000L
        )
        val after = calc.computeBalances(members, listOf(exp), listOf(completedSettlement))
        assertEquals(300.0, after.memberBalances.first { it.uid == "u1" }.netBalance, 0.001)
        assertEquals(0.0, after.memberBalances.first { it.uid == "u2" }.netBalance, 0.001)
        assertEquals(1, after.simplifiedDebts.size)
    }

    @Test
    fun invitationAcceptAndReject_blocksDoubleResponse() {
        val inv = GroupInvitation(
            id = "inv1",
            groupId = "grp1",
            groupName = "Flat 402",
            groupType = GroupType.ROOMMATES,
            inviterUid = "u1",
            inviterName = "Aarav",
            inviteMethod = InviteMethod.APP_ID,
            inviteToken = "INV-123",
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        val accepted = calc.respondToInvitation(inv, "u2", accept = true).getOrThrow()
        assertEquals(InvitationStatus.ACCEPTED, accepted.status)
        assertTrue(calc.respondToInvitation(accepted, "u2", accept = false).isFailure)
    }

    @Test
    fun leaveGroup_blocksUnsettledMemberAndAllowsSettledMember() {
        val members = listOf(member("u1", "Aarav", GroupMemberRole.ADMIN), member("u2", "Rohan"))
        val exp = GroupExpense(
            id = "e1",
            groupId = "grp1",
            memberUids = listOf("u1", "u2"),
            paidByUid = "u1",
            paidByName = "Aarav",
            amount = 200.0,
            description = "Dinner",
            category = ExpenseCategory.Food,
            transactionDate = "2026-11-10",
            transactionTime = "20:00",
            timestampMillis = 1000L,
            splitType = SplitType.EQUAL,
            participantUids = listOf("u1", "u2"),
            shares = mapOf("u1" to 100.0, "u2" to 100.0),
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        val blocked = calc.leaveGroup(group(), members, "u2", listOf(exp), emptyList())
        assertTrue(blocked.isFailure)

        val settled = Settlement(
            id = "s1",
            groupId = "grp1",
            groupName = "Flat 402",
            memberUids = listOf("u1", "u2"),
            payerUid = "u2",
            payerName = "Rohan",
            receiverUid = "u1",
            receiverName = "Aarav",
            amount = 100.0,
            status = SettlementStatus.COMPLETED,
            idempotencyKey = "k2",
            createdAtMillis = 2000L,
            updatedAtMillis = 2000L
        )
        val allowed = calc.leaveGroup(group(), members, "u1", listOf(exp), listOf(settled))
        assertTrue(allowed.isSuccess)
        val (updatedGroup, updatedMembers) = allowed.getOrThrow()
        assertFalse("u1" in updatedGroup.memberUids)
        assertEquals(GroupMemberRole.ADMIN, updatedMembers.first { it.uid == "u2" }.role)
    }

    @Test
    fun dueDateCategorization_classifiesUpcomingDueOverdueCompleted() {
        val base = Settlement(
            id = "s1",
            groupId = "grp1",
            groupName = "Flat 402",
            memberUids = listOf("u1", "u2"),
            payerUid = "u2",
            payerName = "Rohan",
            receiverUid = "u1",
            receiverName = "Aarav",
            amount = 100.0,
            dueDate = "2026-11-15",
            idempotencyKey = "k",
            createdAtMillis = 1L,
            updatedAtMillis = 1L
        )
        assertEquals(SettlementDueCategory.OVERDUE, calc.categorizeSettlementDue(base.copy(dueDate = "2026-11-12"), "2026-11-15"))
        assertEquals(SettlementDueCategory.DUE, calc.categorizeSettlementDue(base, "2026-11-15"))
        assertEquals(SettlementDueCategory.UPCOMING, calc.categorizeSettlementDue(base.copy(dueDate = "2026-11-20"), "2026-11-15"))
        assertEquals(SettlementDueCategory.COMPLETED, calc.categorizeSettlementDue(base.copy(status = SettlementStatus.COMPLETED), "2026-11-20"))
    }

    @Test
    fun upiVpaAndUriBuilderParser_validatesAndRejectsSecrets() {
        assertTrue(calc.validateUpiId("my_pin_1234@okaxis").isFailure)
        assertTrue(calc.validateUpiId("rohan.k@okicici").isSuccess)
        val uri = calc.buildUpiPaymentUri("rohan.k@okicici", "Rohan", 250.5, "Flat rent", "SEM1001")
        val parsed = calc.parseUpiPaymentUri(uri)
        assertTrue(parsed.valid)
        assertEquals("rohan.k@okicici", parsed.upiId)
        assertEquals(250.5, parsed.amount, 0.001)
        assertEquals("SEM1001", parsed.transactionRef)
    }

    @Test
    fun twoPartySettlementStateMachine_preventsUnverifiedCompletionAndDuplicateUtr() {
        val base = Settlement(
            id = "s1",
            groupId = "grp1",
            groupName = "Flat 402",
            memberUids = listOf("u1", "u2"),
            payerUid = "u2",
            payerName = "Rohan",
            receiverUid = "u1",
            receiverName = "Aarav",
            receiverUpiId = "aarav@okaxis",
            amount = 500.0,
            idempotencyKey = "idem_1",
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        // Payer cannot confirm receipt directly
        assertTrue(calc.transitionSettlementStatus(base, emptyList(), "u2", SettlementAction.RECEIVER_CONFIRM).isFailure)

        val initiated = calc.transitionSettlementStatus(base, emptyList(), "u2", SettlementAction.INITIATE_UPI, paymentApp = "GPay").getOrThrow()
        assertEquals(SettlementStatus.PAYMENT_INITIATED, initiated.status)

        val awaiting = calc.transitionSettlementStatus(initiated, emptyList(), "u2", SettlementAction.SUBMIT_PAYER_PROOF, paymentReference = "UTR998877").getOrThrow()
        assertEquals(SettlementStatus.AWAITING_CONFIRMATION, awaiting.status)

        val completed = calc.transitionSettlementStatus(awaiting, listOf(awaiting), "u1", SettlementAction.RECEIVER_CONFIRM).getOrThrow()
        assertEquals(SettlementStatus.COMPLETED, completed.status)

        // Duplicate settlement or duplicate UTR blocked
        assertTrue(calc.transitionSettlementStatus(completed, listOf(completed), "u1", SettlementAction.RECEIVER_CONFIRM).isFailure)
        val second = base.copy(id = "s2", status = SettlementStatus.PAYMENT_INITIATED)
        assertTrue(calc.transitionSettlementStatus(second, listOf(completed), "u2", SettlementAction.SUBMIT_PAYER_PROOF, paymentReference = "UTR998877").isFailure)
    }

    @Test
    fun offlineExpenseReconciliation_keepsPendingUploadAndRespectsPendingDelete() {
        val local = listOf(
            GroupExpense(
                id = "e_del", groupId = "grp1", memberUids = listOf("u1"),
                paidByUid = "u1", paidByName = "Aarav", amount = 50.0, description = "Old",
                category = ExpenseCategory.Food, transactionDate = "2026-11-10", transactionTime = "12:00",
                timestampMillis = 1000L, splitType = SplitType.EQUAL, participantUids = listOf("u1"),
                shares = mapOf("u1" to 50.0), deleted = true, syncStatus = SyncStatus.PENDING_DELETE,
                createdAtMillis = 1000L, updatedAtMillis = 2000L
            ),
            GroupExpense(
                id = "e_new", groupId = "grp1", memberUids = listOf("u1"),
                paidByUid = "u1", paidByName = "Aarav", amount = 180.0, description = "Offline Chai",
                category = ExpenseCategory.Food, transactionDate = "2026-11-15", transactionTime = "09:00",
                timestampMillis = 3000L, splitType = SplitType.EQUAL, participantUids = listOf("u1"),
                shares = mapOf("u1" to 180.0), deleted = false, syncStatus = SyncStatus.PENDING_UPLOAD,
                createdAtMillis = 3000L, updatedAtMillis = 3000L
            )
        )
        val cloud = listOf(
            local[0].copy(deleted = false, syncStatus = SyncStatus.SYNCED),
            GroupExpense(
                id = "e_cloud", groupId = "grp1", memberUids = listOf("u1"),
                paidByUid = "u1", paidByName = "Aarav", amount = 300.0, description = "Milk",
                category = ExpenseCategory.Groceries, transactionDate = "2026-11-12", transactionTime = "08:00",
                timestampMillis = 2000L, splitType = SplitType.EQUAL, participantUids = listOf("u1"),
                shares = mapOf("u1" to 300.0), deleted = false, syncStatus = SyncStatus.SYNCED,
                createdAtMillis = 2000L, updatedAtMillis = 2000L
            )
        )
        val reconciled = calc.reconcileOfflineExpenses(local, cloud)
        assertEquals(2, reconciled.size)
        assertTrue(reconciled.none { it.id == "e_del" })
        assertTrue(reconciled.any { it.id == "e_new" && it.syncStatus == SyncStatus.PENDING_UPLOAD })
    }
}
