package com.smartexpense.manager.domain.calculator

import com.smartexpense.manager.domain.model.GroupBalanceSummary
import com.smartexpense.manager.domain.model.GroupExpense
import com.smartexpense.manager.domain.model.GroupInvitation
import com.smartexpense.manager.domain.model.GroupMember
import com.smartexpense.manager.domain.model.GroupMemberRole
import com.smartexpense.manager.domain.model.GroupMemberStatus
import com.smartexpense.manager.domain.model.InvitationStatus
import com.smartexpense.manager.domain.model.MemberBalance
import com.smartexpense.manager.domain.model.Settlement
import com.smartexpense.manager.domain.model.SettlementDueCategory
import com.smartexpense.manager.domain.model.SettlementPaymentMethod
import com.smartexpense.manager.domain.model.SettlementStatus
import com.smartexpense.manager.domain.model.SimplifiedDebt
import com.smartexpense.manager.domain.model.SplitGroup
import com.smartexpense.manager.domain.model.SplitType
import com.smartexpense.manager.domain.model.SyncStatus
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

data class ParsedUpiUri(
    val valid: Boolean,
    val upiId: String = "",
    val payeeName: String = "",
    val amount: Double = 0.0,
    val note: String = "",
    val transactionRef: String = "",
    val error: String? = null
)

enum class SettlementAction {
    INITIATE_UPI,
    SUBMIT_PAYER_PROOF,
    RECEIVER_CONFIRM,
    REJECT,
    MARK_FAILED
}

@Singleton
class SplitExpenseCalculator @Inject constructor() {

    private val vpaRegex = Regex("^[a-zA-Z0-9._-]{2,64}@[a-zA-Z0-9.-]{2,32}$")
    private val secretKeywordRegex = Regex("pin|otp|password|cvv", RegexOption.IGNORE_CASE)

    fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    fun generateAppUserCode(uid: String): String {
        var hash = 0x811c9dc5u
        for (ch in uid) {
            hash = hash xor ch.code.toUInt()
            hash *= 0x01000193u
        }
        val suffix = hash.toString(36).uppercase().padStart(7, '0').take(7)
        val head = uid.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().take(2).padEnd(2, 'X')
        return "SEM-$head$suffix"
    }

    fun generateJoinCode(groupId: String): String {
        var hash = 5381u
        for (ch in groupId) {
            hash = (hash * 33u) xor ch.code.toUInt()
        }
        return "GRP-${hash.toString(36).uppercase().padStart(6, '0').take(6)}"
    }

    fun validateUpiId(raw: String): Result<String> {
        val normalized = raw.trim().lowercase()
        if (normalized.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a UPI ID such as name@okaxis."))
        }
        if (secretKeywordRegex.containsMatchIn(normalized)) {
            return Result.failure(IllegalArgumentException("Never enter UPI PIN, OTP, or banking passwords."))
        }
        if (!vpaRegex.matches(normalized)) {
            return Result.failure(IllegalArgumentException("Invalid UPI ID format. Expected handle@bank."))
        }
        return Result.success(normalized)
    }

    fun calculateSplitShares(
        totalAmount: Double,
        splitType: SplitType,
        participantUids: List<String>,
        customInputs: Map<String, Double> = emptyMap()
    ): Result<Map<String, Double>> {
        if (totalAmount <= 0.0 || totalAmount.isNaN() || totalAmount.isInfinite()) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero."))
        }
        val uniqueUids = participantUids.filter { it.isNotBlank() }.distinct()
        if (uniqueUids.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one member to split with."))
        }

        val totalCents = (totalAmount * 100.0).roundToLong()
        val shares = linkedMapOf<String, Double>()

        when (splitType) {
            SplitType.EQUAL -> {
                val baseCents = totalCents / uniqueUids.size
                var remainder = (totalCents - baseCents * uniqueUids.size).toInt()
                for (uid in uniqueUids) {
                    val extra = if (remainder > 0) 1L else 0L
                    if (remainder > 0) remainder--
                    shares[uid] = (baseCents + extra) / 100.0
                }
            }
            SplitType.CUSTOM_AMOUNT -> {
                var sumCents = 0L
                for (uid in uniqueUids) {
                    val v = customInputs[uid] ?: 0.0
                    if (v < 0.0 || v.isNaN()) {
                        return Result.failure(IllegalArgumentException("Custom split amounts cannot be negative."))
                    }
                    val cents = (v * 100.0).roundToLong()
                    shares[uid] = cents / 100.0
                    sumCents += cents
                }
                if (abs(sumCents - totalCents) > 1L) {
                    return Result.failure(IllegalArgumentException("Custom amounts must equal the total amount."))
                }
                if (sumCents != totalCents) {
                    val last = uniqueUids.last()
                    shares[last] = round2((shares[last] ?: 0.0) + (totalCents - sumCents) / 100.0)
                }
            }
            SplitType.PERCENTAGE -> {
                var pctSum = 0.0
                for (uid in uniqueUids) {
                    val pct = customInputs[uid] ?: 0.0
                    if (pct < 0.0 || pct > 100.0) {
                        return Result.failure(IllegalArgumentException("Percentages must be between 0 and 100."))
                    }
                    pctSum += pct
                }
                if (abs(pctSum - 100.0) > 0.05) {
                    return Result.failure(IllegalArgumentException("Percentages must total 100%."))
                }
                var allocated = 0L
                uniqueUids.forEachIndexed { idx, uid ->
                    if (idx == uniqueUids.lastIndex) {
                        shares[uid] = (totalCents - allocated) / 100.0
                    } else {
                        val cents = ((totalCents * (customInputs[uid] ?: 0.0)) / 100.0).roundToLong()
                        allocated += cents
                        shares[uid] = cents / 100.0
                    }
                }
            }
            SplitType.SHARES -> {
                val totalWeight = uniqueUids.sumOf { uid ->
                    val w = customInputs[uid] ?: 0.0
                    if (w < 0.0) return Result.failure(IllegalArgumentException("Share weights cannot be negative."))
                    w
                }
                if (totalWeight <= 0.0) {
                    return Result.failure(IllegalArgumentException("Total share weights must be positive."))
                }
                var allocated = 0L
                uniqueUids.forEachIndexed { idx, uid ->
                    if (idx == uniqueUids.lastIndex) {
                        shares[uid] = (totalCents - allocated) / 100.0
                    } else {
                        val cents = ((totalCents * (customInputs[uid] ?: 0.0)) / totalWeight).roundToLong()
                        allocated += cents
                        shares[uid] = cents / 100.0
                    }
                }
            }
        }
        return Result.success(shares)
    }

    fun computeBalances(
        members: List<GroupMember>,
        expenses: List<GroupExpense>,
        settlements: List<Settlement>
    ): GroupBalanceSummary {
        val memberMap = members.associateBy { it.uid }
        val paidMap = linkedMapOf<String, Double>()
        val shareMap = linkedMapOf<String, Double>()
        val settledOutMap = linkedMapOf<String, Double>()
        val settledInMap = linkedMapOf<String, Double>()

        fun ensure(uid: String) {
            paidMap.putIfAbsent(uid, 0.0)
            shareMap.putIfAbsent(uid, 0.0)
            settledOutMap.putIfAbsent(uid, 0.0)
            settledInMap.putIfAbsent(uid, 0.0)
        }
        members.forEach { ensure(it.uid) }

        var totalGroup = 0.0
        for (exp in expenses) {
            if (exp.deleted || exp.syncStatus == SyncStatus.PENDING_DELETE) continue
            totalGroup += exp.amount
            ensure(exp.paidByUid)
            paidMap[exp.paidByUid] = round2((paidMap[exp.paidByUid] ?: 0.0) + exp.amount)
            exp.shares.forEach { (uid, sAmt) ->
                ensure(uid)
                shareMap[uid] = round2((shareMap[uid] ?: 0.0) + sAmt)
            }
        }

        for (st in settlements) {
            if (st.status != SettlementStatus.COMPLETED) continue
            ensure(st.payerUid)
            ensure(st.receiverUid)
            settledOutMap[st.payerUid] = round2((settledOutMap[st.payerUid] ?: 0.0) + st.amount)
            settledInMap[st.receiverUid] = round2((settledInMap[st.receiverUid] ?: 0.0) + st.amount)
        }

        val allUids = (members.map { it.uid } + paidMap.keys + shareMap.keys).distinct()
        val balances = allUids.map { uid ->
            val meta = memberMap[uid]
            val paid = round2(paidMap[uid] ?: 0.0)
            val share = round2(shareMap[uid] ?: 0.0)
            val sOut = round2(settledOutMap[uid] ?: 0.0)
            val sIn = round2(settledInMap[uid] ?: 0.0)
            val net = round2(paid - share + sOut - sIn)
            MemberBalance(
                uid = uid,
                displayName = meta?.displayName ?: uid,
                appUserCode = meta?.appUserCode ?: generateAppUserCode(uid),
                upiId = meta?.upiId ?: "",
                upiVerified = meta?.upiVerified ?: false,
                role = meta?.role ?: GroupMemberRole.MEMBER,
                status = meta?.status ?: GroupMemberStatus.ACTIVE,
                totalPaid = paid,
                totalFairShare = share,
                settledPaid = sOut,
                settledReceived = sIn,
                netBalance = net
            )
        }

        data class Bucket(val member: MemberBalance, var remaining: Double)
        val debtors = balances.filter { it.netBalance < -0.005 }
            .map { Bucket(it, round2(-it.netBalance)) }
            .sortedByDescending { it.remaining }
        val creditors = balances.filter { it.netBalance > 0.005 }
            .map { Bucket(it, round2(it.netBalance)) }
            .sortedByDescending { it.remaining }

        val debts = mutableListOf<SimplifiedDebt>()
        var i = 0
        var j = 0
        while (i < debtors.size && j < creditors.size) {
            val d = debtors[i]
            val c = creditors[j]
            val amt = round2(minOf(d.remaining, c.remaining))
            if (amt > 0.005) {
                debts.add(
                    SimplifiedDebt(
                        fromUid = d.member.uid,
                        fromName = d.member.displayName,
                        toUid = c.member.uid,
                        toName = c.member.displayName,
                        toUpiId = c.member.upiId,
                        toUpiVerified = c.member.upiVerified,
                        amount = amt
                    )
                )
            }
            d.remaining = round2(d.remaining - amt)
            c.remaining = round2(c.remaining - amt)
            if (d.remaining <= 0.005) i++
            if (c.remaining <= 0.005) j++
        }

        return GroupBalanceSummary(
            totalGroupExpenses = round2(totalGroup),
            memberBalances = balances,
            simplifiedDebts = debts
        )
    }

    fun categorizeSettlementDue(settlement: Settlement, todayDate: String): SettlementDueCategory {
        if (settlement.status == SettlementStatus.COMPLETED) return SettlementDueCategory.COMPLETED
        if (settlement.dueDate.isBlank()) return SettlementDueCategory.UPCOMING
        return when {
            settlement.dueDate < todayDate -> SettlementDueCategory.OVERDUE
            settlement.dueDate == todayDate -> SettlementDueCategory.DUE
            else -> SettlementDueCategory.UPCOMING
        }
    }

    fun buildUpiPaymentUri(
        upiId: String,
        payeeName: String,
        amount: Double,
        note: String,
        transactionRef: String
    ): String {
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
        val formattedAmt = String.format(java.util.Locale.US, "%.2f", round2(amount))
        return "upi://pay?pa=${enc(upiId.trim().lowercase())}" +
            "&pn=${enc(payeeName.trim().take(60))}" +
            "&am=$formattedAmt" +
            "&cu=INR" +
            "&tn=${enc(note.trim().take(80))}" +
            "&tr=${enc(transactionRef.trim().take(35))}"
    }

    fun parseUpiPaymentUri(rawUri: String): ParsedUpiUri {
        val trimmed = rawUri.trim()
        if (!trimmed.lowercase().startsWith("upi://pay")) {
            return ParsedUpiUri(valid = false, error = "QR code is not a valid UPI payment URI.")
        }
        val queryPart = trimmed.substringAfter('?', "")
        val params = queryPart.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else {
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                k to v
            }
        }.toMap()

        val upiId = params["pa"]?.trim()?.lowercase().orEmpty()
        val vpaRes = validateUpiId(upiId)
        if (vpaRes.isFailure) {
            return ParsedUpiUri(valid = false, error = vpaRes.exceptionOrNull()?.message)
        }
        val amt = params["am"]?.toDoubleOrNull()?.let { if (it > 0) round2(it) else 0.0 } ?: 0.0
        return ParsedUpiUri(
            valid = true,
            upiId = vpaRes.getOrThrow(),
            payeeName = params["pn"].orEmpty(),
            amount = amt,
            note = params["tn"].orEmpty(),
            transactionRef = params["tr"].orEmpty()
        )
    }

    fun transitionSettlementStatus(
        settlement: Settlement,
        existingSettlements: List<Settlement>,
        actorUid: String,
        action: SettlementAction,
        paymentApp: String = "",
        paymentReference: String = "",
        paymentMethod: SettlementPaymentMethod = settlement.paymentMethod,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<Settlement> {
        if (settlement.status == SettlementStatus.COMPLETED) {
            return Result.failure(IllegalStateException("Duplicate settlement blocked: already COMPLETED."))
        }
        return when (action) {
            SettlementAction.INITIATE_UPI -> {
                if (actorUid != settlement.payerUid) {
                    Result.failure(IllegalAccessException("Only payer can initiate UPI settlement."))
                } else {
                    Result.success(
                        settlement.copy(
                            status = SettlementStatus.PAYMENT_INITIATED,
                            paymentMethod = paymentMethod,
                            paymentApp = paymentApp.ifBlank { "UPI" },
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }
            SettlementAction.SUBMIT_PAYER_PROOF -> {
                val cleanRef = paymentReference.trim()
                if (actorUid != settlement.payerUid) {
                    Result.failure(IllegalAccessException("Only payer can submit payment proof."))
                } else if (cleanRef.length < 4) {
                    Result.failure(IllegalArgumentException("Enter a valid UTR / reference ID (min 4 chars)."))
                } else if (existingSettlements.any {
                        it.id != settlement.id &&
                            it.status == SettlementStatus.COMPLETED &&
                            it.paymentReference.equals(cleanRef, ignoreCase = true)
                    }
                ) {
                    Result.failure(IllegalStateException("Duplicate payment reference: $cleanRef already completed."))
                } else {
                    Result.success(
                        settlement.copy(
                            status = SettlementStatus.AWAITING_CONFIRMATION,
                            paymentReference = cleanRef,
                            paymentMethod = paymentMethod,
                            payerConfirmedAtMillis = nowMillis,
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }
            SettlementAction.RECEIVER_CONFIRM -> {
                if (actorUid != settlement.receiverUid) {
                    Result.failure(IllegalAccessException("Only receiver can confirm receipt."))
                } else if (settlement.status != SettlementStatus.AWAITING_CONFIRMATION &&
                    settlement.status != SettlementStatus.PAYMENT_INITIATED
                ) {
                    Result.failure(IllegalStateException("Payment must be initiated first."))
                } else {
                    val cleanRef = paymentReference.ifBlank { settlement.paymentReference }.trim()
                    if (cleanRef.isNotBlank() && existingSettlements.any {
                            it.id != settlement.id &&
                                it.status == SettlementStatus.COMPLETED &&
                                it.paymentReference.equals(cleanRef, ignoreCase = true)
                        }
                    ) {
                        Result.failure(IllegalStateException("Duplicate payment confirmation blocked."))
                    } else {
                        Result.success(
                            settlement.copy(
                                status = SettlementStatus.COMPLETED,
                                paymentReference = cleanRef,
                                receiverConfirmedAtMillis = nowMillis,
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                }
            }
            SettlementAction.MARK_FAILED -> {
                Result.success(
                    settlement.copy(
                        status = SettlementStatus.FAILED,
                        updatedAtMillis = nowMillis
                    )
                )
            }
            SettlementAction.REJECT -> {
                Result.success(
                    settlement.copy(
                        status = SettlementStatus.REJECTED,
                        updatedAtMillis = nowMillis
                    )
                )
            }
        }
    }

    fun respondToInvitation(
        invitation: GroupInvitation,
        responderUid: String,
        accept: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<GroupInvitation> {
        if (invitation.status != InvitationStatus.PENDING) {
            return Result.failure(IllegalStateException("Invitation is already ${invitation.status}."))
        }
        return Result.success(
            invitation.copy(
                targetUid = responderUid,
                status = if (accept) InvitationStatus.ACCEPTED else InvitationStatus.REJECTED,
                updatedAtMillis = nowMillis
            )
        )
    }

    fun leaveGroup(
        group: SplitGroup,
        members: List<GroupMember>,
        memberUid: String,
        expenses: List<GroupExpense>,
        settlements: List<Settlement>,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<Pair<SplitGroup, List<GroupMember>>> {
        val activeMember = members.firstOrNull { it.uid == memberUid && it.status == GroupMemberStatus.ACTIVE }
            ?: return Result.failure(IllegalStateException("User is not an active member of this group."))
        val summary = computeBalances(members, expenses, settlements)
        val balance = summary.memberBalances.firstOrNull { it.uid == memberUid }?.netBalance ?: 0.0
        if (abs(balance) > 0.01) {
            return Result.failure(IllegalStateException("Settle your group balance before leaving."))
        }
        val activeOthers = members.filter { it.status == GroupMemberStatus.ACTIVE && it.uid != memberUid }
        val successorUid = if (activeMember.role == GroupMemberRole.ADMIN) activeOthers.firstOrNull()?.uid else null
        val updatedMembers = members.map { m ->
            when {
                m.uid == memberUid -> m.copy(status = GroupMemberStatus.LEFT, updatedAtMillis = nowMillis)
                m.uid == successorUid -> m.copy(role = GroupMemberRole.ADMIN, updatedAtMillis = nowMillis)
                else -> m
            }
        }
        val updatedGroup = group.copy(
            memberUids = group.memberUids.filterNot { it == memberUid },
            updatedAtMillis = nowMillis
        )
        return Result.success(updatedGroup to updatedMembers)
    }

    fun reconcileOfflineExpenses(
        localExpenses: List<GroupExpense>,
        cloudExpenses: List<GroupExpense>
    ): List<GroupExpense> {
        val deletedIds = localExpenses
            .filter { it.deleted || it.syncStatus == SyncStatus.PENDING_DELETE }
            .map { it.id }
            .toSet()
        val pendingUpdates = localExpenses
            .filter { !it.deleted && it.syncStatus == SyncStatus.PENDING_UPLOAD }
        val merged = linkedMapOf<String, GroupExpense>()
        for (c in cloudExpenses) {
            if (c.id !in deletedIds) {
                merged[c.id] = c.copy(syncStatus = SyncStatus.SYNCED)
            }
        }
        for (p in pendingUpdates) {
            val existing = merged[p.id]
            if (existing == null || p.updatedAtMillis >= existing.updatedAtMillis) {
                merged[p.id] = p
            }
        }
        return merged.values.sortedByDescending { it.timestampMillis }
    }
}
