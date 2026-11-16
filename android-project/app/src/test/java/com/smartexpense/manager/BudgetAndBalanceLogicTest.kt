package com.smartexpense.manager

import com.smartexpense.manager.domain.calculator.BalanceCalculator
import com.smartexpense.manager.domain.model.BudgetStatusColor
import com.smartexpense.manager.domain.model.EntrySource
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BudgetAndBalanceLogicTest {

    private val calculator = BalanceCalculator()
    private val novMonth = YearMonth.of(2026, 11)

    private fun makeTx(
        id: String,
        type: TransactionType,
        amount: Double,
        date: String = "2026-11-15",
        source: EntrySource = EntrySource.MANUAL
    ) = Transaction(
        id = id,
        uid = "u_test",
        type = type,
        amount = amount,
        description = "Test $id",
        category = if (type == TransactionType.INCOME) ExpenseCategory.Income else ExpenseCategory.Food,
        transactionDate = date,
        transactionTime = "12:00",
        timestampMillis = calculator.toTimestampMillis(date, "12:00"),
        entrySource = source,
        createdAtMillis = 1000L,
        updatedAtMillis = 1000L
    )

    @Test
    fun `1 budget usage below 50 percent returns GREEN`() {
        val summary = calculator.calculate(
            openingBalance = 20000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 3500.0)),
            targetMonth = novMonth
        )
        assertEquals(35.0, summary.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.GREEN, summary.budgetStatusColor)
        assertFalse(summary.isBudgetExceeded)
    }

    @Test
    fun `2 budget usage at exact 50 percent returns YELLOW`() {
        val summary = calculator.calculate(
            openingBalance = 20000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 5000.0)),
            targetMonth = novMonth
        )
        assertEquals(50.0, summary.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.YELLOW, summary.budgetStatusColor)
        assertFalse(summary.isBudgetExceeded)
    }

    @Test
    fun `3 budget usage above 50 percent returns RED`() {
        val at65 = calculator.calculate(
            openingBalance = 20000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 6500.0)),
            targetMonth = novMonth
        )
        assertEquals(65.0, at65.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.RED, at65.budgetStatusColor)
        assertFalse(at65.isBudgetExceeded)

        val at100 = calculator.calculate(
            openingBalance = 20000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 10000.0)),
            targetMonth = novMonth
        )
        assertEquals(100.0, at100.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.RED, at100.budgetStatusColor)
        assertTrue(at100.isBudgetExceeded)
    }

    @Test
    fun `4 budget usage above 100 percent preserves actual percent and flags exceeded`() {
        val summary = calculator.calculate(
            openingBalance = 30000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 12000.0)),
            targetMonth = novMonth
        )
        assertEquals(120.0, summary.budgetUsagePercent, 0.001)
        assertEquals(-2000.0, summary.remainingBudget, 0.001)
        assertEquals(BudgetStatusColor.RED, summary.budgetStatusColor)
        assertTrue(summary.isBudgetExceeded)
        // Visual fill clamp check mirrors UI logic
        val visualFill = (summary.budgetUsagePercent / 100.0).toFloat().coerceIn(0f, 1f)
        assertEquals(1.0f, visualFill, 0.0001f)
    }

    @Test
    fun `5 zero monthly budget handles division safely without errors`() {
        val summary = calculator.calculate(
            openingBalance = 5000.0,
            monthlyBudget = 0.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 1200.0)),
            targetMonth = novMonth
        )
        assertEquals(0.0, summary.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.GREEN, summary.budgetStatusColor)
        assertFalse(summary.isBudgetExceeded)
        assertEquals(3800.0, summary.availableBalance, 0.001)
    }

    @Test
    fun `6 available balance at zero shows exhausted and blocks expense`() {
        val summary = calculator.calculate(
            openingBalance = 500.0,
            monthlyBudget = 1000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 500.0)),
            targetMonth = novMonth
        )
        assertEquals(0.0, summary.availableBalance, 0.001)
        assertTrue(summary.isBalanceExhausted)

        val validation = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 50.0,
            currentAvailableBalance = summary.availableBalance
        )
        assertFalse(validation.allowed)
        assertEquals("Insufficient Balance!", validation.errorTitle)
        assertEquals(0.0, validation.currentAvailableBalance, 0.001)
        assertEquals(50.0, validation.shortfallAmount, 0.001)
    }

    @Test
    fun `7 expense exceeding available balance is blocked with shortfall calculation`() {
        val validation = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 500.0,
            currentAvailableBalance = 350.0
        )
        assertFalse(validation.allowed)
        assertEquals("Insufficient Balance!", validation.errorTitle)
        assertEquals("You don't have enough money to complete this expense.", validation.errorMessage)
        assertEquals(350.0, validation.currentAvailableBalance, 0.001)
        assertEquals(150.0, validation.shortfallAmount, 0.001)
    }

    @Test
    fun `8 expense equal to remaining available balance succeeds and exhausts balance to zero`() {
        val validation = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 350.0,
            currentAvailableBalance = 350.0
        )
        assertTrue(validation.allowed)
        assertTrue(validation.willExhaustBalance)
        assertEquals(0.0, validation.shortfallAmount, 0.001)

        val nextSummary = calculator.calculate(
            openingBalance = 350.0,
            monthlyBudget = 1000.0,
            transactions = listOf(makeTx("e1", TransactionType.EXPENSE, 350.0)),
            targetMonth = novMonth
        )
        assertEquals(0.0, nextSummary.availableBalance, 0.001)
        assertTrue(nextSummary.isBalanceExhausted)
    }

    @Test
    fun `9 adding income increases available balance and allows subsequent expenses`() {
        val incomeTx = makeTx("inc1", TransactionType.INCOME, 2500.0)
        val summary = calculator.calculate(
            openingBalance = 0.0,
            monthlyBudget = 5000.0,
            transactions = listOf(incomeTx),
            targetMonth = novMonth
        )
        assertEquals(2500.0, summary.availableBalance, 0.001)
        assertFalse(summary.isBalanceExhausted)

        val validation = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 2000.0,
            currentAvailableBalance = summary.availableBalance
        )
        assertTrue(validation.allowed)
        assertFalse(validation.willExhaustBalance)
    }

    @Test
    fun `10 editing and deleting transactions recalculate balance and exclude prior amount`() {
        val existingExpense = makeTx("e1", TransactionType.EXPENSE, 200.0)
        // Opening 500 - 200 = 300 current available balance
        val summary = calculator.calculate(
            openingBalance = 500.0,
            monthlyBudget = 1000.0,
            transactions = listOf(existingExpense),
            targetMonth = novMonth
        )
        assertEquals(300.0, summary.availableBalance, 0.001)

        // Editing e1 from 200 to 450 excludes old 200 -> effective balance 500 -> allowed!
        val validEdit = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 450.0,
            currentAvailableBalance = summary.availableBalance,
            existingTransaction = existingExpense
        )
        assertTrue(validEdit.allowed)
        assertEquals(500.0, validEdit.currentAvailableBalance, 0.001)

        // Editing e1 from 200 to 600 exceeds effective balance 500 -> blocked with shortfall 100
        val invalidEdit = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 600.0,
            currentAvailableBalance = summary.availableBalance,
            existingTransaction = existingExpense
        )
        assertFalse(invalidEdit.allowed)
        assertEquals(500.0, invalidEdit.currentAvailableBalance, 0.001)
        assertEquals(100.0, invalidEdit.shortfallAmount, 0.001)

        // Deleting e1 restores balance to 500 and monthly budget usage to 0%
        val afterDelete = calculator.calculate(
            openingBalance = 500.0,
            monthlyBudget = 1000.0,
            transactions = emptyList(),
            targetMonth = novMonth
        )
        assertEquals(500.0, afterDelete.availableBalance, 0.001)
        assertEquals(0.0, afterDelete.budgetUsagePercent, 0.001)
        assertEquals(BudgetStatusColor.GREEN, afterDelete.budgetStatusColor)
    }

    @Test
    fun `11 exceeded monthly budget still permits receipt or manual expense if available balance is sufficient`() {
        val priorExpense = makeTx("e1", TransactionType.EXPENSE, 11000.0)
        val summary = calculator.calculate(
            openingBalance = 13000.0,
            monthlyBudget = 10000.0,
            transactions = listOf(priorExpense),
            targetMonth = novMonth
        )
        assertEquals(2000.0, summary.availableBalance, 0.001)
        assertEquals(110.0, summary.budgetUsagePercent, 0.001)
        assertTrue(summary.isBudgetExceeded)
        assertEquals(BudgetStatusColor.RED, summary.budgetStatusColor)

        // Receipt import expense of ₹1,500 must be allowed since available balance is ₹2,000
        val receiptValidation = calculator.validateTransactionBalance(
            type = TransactionType.EXPENSE,
            newAmount = 1500.0,
            currentAvailableBalance = summary.availableBalance
        )
        assertTrue(receiptValidation.allowed)
        assertFalse(receiptValidation.willExhaustBalance)
    }
}
