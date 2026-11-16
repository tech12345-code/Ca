package com.smartexpense.manager.domain.calculator

import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.BalanceValidationResult
import com.smartexpense.manager.domain.model.BudgetStatusColor
import com.smartexpense.manager.domain.model.LedgerEntry
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class BalanceCalculator @Inject constructor() {

    /**
     * Deterministic chronological ledger engine:
     * 1. Sorts all transactions strictly by actual transaction date & time (timestampMillis ASC, then createdAtMillis ASC).
     * 2. Computes running balance starting from Opening Balance:
     *      Opening Balance + Total Income - Total Expenses = Available Balance
     * 3. Computes monthly budget usage separately from total available balance.
     */
    fun calculate(
        openingBalance: Double,
        monthlyBudget: Double,
        transactions: List<Transaction>,
        targetMonth: YearMonth = YearMonth.now()
    ): BalanceSummary {
        val chronological = transactions.sortedWith(
            compareBy<Transaction> { it.timestampMillis }
                .thenBy { it.createdAtMillis }
                .thenBy { it.id }
        )

        var runningBalance = openingBalance
        var totalIncome = 0.0
        var totalExpenses = 0.0
        var monthlySpent = 0.0

        val monthPrefix = targetMonth.toString() // YYYY-MM

        val ledger = chronological.map { tx ->
            if (tx.type == TransactionType.INCOME) {
                totalIncome += tx.amount
                runningBalance += tx.amount
            } else {
                totalExpenses += tx.amount
                runningBalance -= tx.amount
                if (tx.transactionDate.startsWith(monthPrefix)) {
                    monthlySpent += tx.amount
                }
            }
            LedgerEntry(
                transaction = tx,
                runningBalanceAfter = normalizeZero(roundTwoDecimals(runningBalance))
            )
        }

        val availableBalance = normalizeZero(roundTwoDecimals(openingBalance + totalIncome - totalExpenses))
        val cleanBudget = normalizeZero(roundTwoDecimals(monthlyBudget.coerceAtLeast(0.0)))
        val cleanMonthlySpent = normalizeZero(roundTwoDecimals(monthlySpent))
        val remainingBudget = normalizeZero(roundTwoDecimals(cleanBudget - cleanMonthlySpent))
        val usagePercent = if (cleanBudget > 0.0) {
            normalizeZero(roundTwoDecimals((cleanMonthlySpent / cleanBudget) * 100.0))
        } else {
            0.0
        }
        val statusColor = getBudgetColorState(cleanMonthlySpent, cleanBudget)
        val isBudgetExceeded = cleanBudget > 0.0 && usagePercent >= 100.0
        val isBalanceExhausted = abs(availableBalance) < 0.005

        return BalanceSummary(
            openingBalance = normalizeZero(roundTwoDecimals(openingBalance)),
            totalIncome = normalizeZero(roundTwoDecimals(totalIncome)),
            totalExpenses = normalizeZero(roundTwoDecimals(totalExpenses)),
            availableBalance = availableBalance,
            monthlyBudget = cleanBudget,
            monthlySpent = cleanMonthlySpent,
            remainingBudget = remainingBudget,
            budgetUsagePercent = usagePercent,
            ledger = ledger,
            budgetStatusColor = statusColor,
            isBudgetExceeded = isBudgetExceeded,
            isBalanceExhausted = isBalanceExhausted
        )
    }

    /**
     * Dynamic colour rule shared identically across Main Dashboard and Monthly Analytics:
     * - Below 50% -> GREEN
     * - Exactly 50% -> YELLOW
     * - Above 50% -> RED
     * - Zero monthly budget -> GREEN safely without division errors.
     */
    fun getBudgetColorState(monthlySpent: Double, monthlyBudget: Double): BudgetStatusColor {
        if (monthlyBudget <= 0.0) return BudgetStatusColor.GREEN
        val percent = roundTwoDecimals((monthlySpent / monthlyBudget) * 100.0)
        return when {
            abs(percent - 50.0) < 0.005 -> BudgetStatusColor.YELLOW
            percent < 50.0 -> BudgetStatusColor.GREEN
            else -> BudgetStatusColor.RED
        }
    }

    /**
     * When editing an existing transaction, removes the existing transaction's impact
     * from the current available balance before validating the updated transaction.
     */
    fun calculateEffectiveAvailableBalance(
        currentAvailableBalance: Double,
        existingTransaction: Transaction?
    ): Double {
        if (existingTransaction == null) return normalizeZero(roundTwoDecimals(currentAvailableBalance))
        val adjusted = if (existingTransaction.type == TransactionType.EXPENSE) {
            currentAvailableBalance + existingTransaction.amount
        } else {
            currentAvailableBalance - existingTransaction.amount
        }
        return normalizeZero(roundTwoDecimals(adjusted))
    }

    /**
     * Real-time available balance validation before recording or updating a transaction:
     * - Strictly independent of Monthly Budget.
     * - Blocks expenses exceeding effective available balance (never allows negative balance from expenses).
     * - Flags exact exhaustion when resulting available balance reaches ₹0.
     */
    fun validateTransactionBalance(
        type: TransactionType,
        newAmount: Double,
        currentAvailableBalance: Double,
        existingTransaction: Transaction? = null
    ): BalanceValidationResult {
        val effectiveAvailable = calculateEffectiveAvailableBalance(currentAvailableBalance, existingTransaction)

        if (type == TransactionType.INCOME) {
            val resultingBalance = normalizeZero(roundTwoDecimals(effectiveAvailable + newAmount))
            if (resultingBalance < -0.005) {
                val shortfall = roundTwoDecimals(-resultingBalance)
                return BalanceValidationResult(
                    allowed = false,
                    currentAvailableBalance = effectiveAvailable,
                    shortfallAmount = shortfall,
                    willExhaustBalance = false,
                    errorTitle = "Insufficient Balance!",
                    errorMessage = "Reducing this income entry would push your available balance below zero."
                )
            }
            return BalanceValidationResult(
                allowed = true,
                currentAvailableBalance = effectiveAvailable,
                shortfallAmount = 0.0,
                willExhaustBalance = abs(resultingBalance) < 0.005
            )
        }

        val cleanAmount = roundTwoDecimals(newAmount)
        if (cleanAmount > effectiveAvailable + 0.001) {
            val shortfall = roundTwoDecimals((cleanAmount - effectiveAvailable).coerceAtLeast(0.0))
            return BalanceValidationResult(
                allowed = false,
                currentAvailableBalance = effectiveAvailable,
                shortfallAmount = shortfall,
                willExhaustBalance = false,
                errorTitle = "Insufficient Balance!",
                errorMessage = "You don't have enough money to complete this expense."
            )
        }

        val remainingAfter = normalizeZero(roundTwoDecimals(effectiveAvailable - cleanAmount))
        return BalanceValidationResult(
            allowed = true,
            currentAvailableBalance = effectiveAvailable,
            shortfallAmount = 0.0,
            willExhaustBalance = abs(remainingAfter) < 0.005
        )
    }

    fun toTimestampMillis(dateStr: String, timeStr: String): Long {
        val date = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }
        val normalizedTime = if (timeStr.length == 5) "$timeStr:00" else timeStr
        val time = runCatching { LocalTime.parse(normalizedTime) }.getOrElse { LocalTime.MIDNIGHT }
        return LocalDateTime.of(date, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun formatDateTime(epochMillis: Long): Pair<String, String> {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return ldt.toLocalDate().toString() to "%02d:%02d".format(ldt.hour, ldt.minute)
    }

    private fun roundTwoDecimals(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0

    private fun normalizeZero(value: Double): Double =
        if (abs(value) < 0.005) 0.0 else value
}
