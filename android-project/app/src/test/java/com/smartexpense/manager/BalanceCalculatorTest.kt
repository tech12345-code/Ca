package com.smartexpense.manager

import com.smartexpense.manager.domain.calculator.BalanceCalculator
import com.smartexpense.manager.domain.model.EntrySource
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class BalanceCalculatorTest {

    private val calculator = BalanceCalculator()

    @Test
    fun `chronological insertion recalculates running balances accurately`() {
        val txNov15 = Transaction(
            id = "tx_2",
            uid = "u1",
            type = TransactionType.EXPENSE,
            amount = 50.0,
            description = "Coffee",
            category = ExpenseCategory.Food,
            transactionDate = "2026-11-15",
            transactionTime = "10:00",
            timestampMillis = calculator.toTimestampMillis("2026-11-15", "10:00"),
            entrySource = EntrySource.MANUAL,
            createdAtMillis = 2000L,
            updatedAtMillis = 2000L
        )
        // Older transaction inserted AFTER newer transaction
        val txNov14 = Transaction(
            id = "tx_1",
            uid = "u1",
            type = TransactionType.EXPENSE,
            amount = 80.0,
            description = "Dinner",
            category = ExpenseCategory.Food,
            transactionDate = "2026-11-14",
            transactionTime = "20:00",
            timestampMillis = calculator.toTimestampMillis("2026-11-14", "20:00"),
            entrySource = EntrySource.MANUAL,
            createdAtMillis = 3000L,
            updatedAtMillis = 3000L
        )

        val summary = calculator.calculate(
            openingBalance = 1000.0,
            monthlyBudget = 500.0,
            transactions = listOf(txNov15, txNov14),
            targetMonth = YearMonth.of(2026, 11)
        )

        assertEquals(870.0, summary.availableBalance, 0.001)
        assertEquals("tx_1", summary.ledger[0].transaction.id)
        assertEquals(920.0, summary.ledger[0].runningBalanceAfter, 0.001)
        assertEquals("tx_2", summary.ledger[1].transaction.id)
        assertEquals(870.0, summary.ledger[1].runningBalanceAfter, 0.001)
        assertEquals(130.0, summary.monthlySpent, 0.001)
        assertEquals(370.0, summary.remainingBudget, 0.001)
    }
}
