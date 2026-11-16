package com.smartexpense.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.domain.model.UserProfile
import com.smartexpense.manager.ui.components.BalanceExhaustedBanner
import com.smartexpense.manager.ui.components.DynamicMonthlyBudgetSection
import com.smartexpense.manager.ui.theme.SmartColors

@Composable
fun DashboardScreen(
    profile: UserProfile,
    summary: BalanceSummary,
    onAddExpense: (ExpenseCategory?) -> Unit,
    onAddBalance: () -> Unit,
    onImportReceipt: () -> Unit,
    onOpenLedger: () -> Unit,
    onSelectTransaction: (Transaction) -> Unit
) {
    val currency = profile.currencySymbol

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SmartColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Hello, ${profile.displayName}",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Available Balance · Chronological Ledger",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartColors.TextMuted
                )
            }
        }

        // Balance Hero & Dynamic Monthly Budget Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(20.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("TOTAL AVAILABLE BALANCE", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$currency${"%,.2f".format(summary.availableBalance)}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (summary.isBalanceExhausted) SmartColors.ExpenseOrange else SmartColors.NeonLime
                )
                if (summary.isBalanceExhausted) {
                    Spacer(Modifier.height(10.dp))
                    BalanceExhaustedBanner()
                }
                Spacer(Modifier.height(16.dp))
                DynamicMonthlyBudgetSection(currency = currency, summary = summary)
            }
        }

        // Primary Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onAddExpense(null) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.ExpenseOrange)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(" Add Expense", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onAddBalance,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmartColors.NeonLime,
                        contentColor = SmartColors.Background
                    )
                ) {
                    Icon(Icons.Rounded.SouthWest, contentDescription = null)
                    Text(" Add Balance", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onImportReceipt,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.DocumentScanner, contentDescription = null, tint = SmartColors.NeonLime)
                Text("  IMPORT PAYMENT RECEIPT (Camera / Gallery / PDF)", color = SmartColors.TextPrimary)
            }
        }

        // Quick Categories
        item {
            Text("Quick Categories", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ExpenseCategory.expenseCategories) { category ->
                    Box(
                        modifier = Modifier
                            .background(SmartColors.Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SmartColors.Border, RoundedCornerShape(12.dp))
                            .clickable { onAddExpense(category) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(category.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Recent Transactions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent Transactions", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "View All",
                    color = SmartColors.NeonLime,
                    modifier = Modifier.clickable { onOpenLedger() }
                )
            }
        }

        val recent = summary.ledger.reversed().take(8)
        if (recent.isEmpty()) {
            item {
                Text(
                    text = "No transactions recorded yet. Tap Add Expense, Add Balance, or Import Payment Receipt.",
                    color = SmartColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items(recent, key = { it.transaction.id }) { entry ->
                val tx = entry.transaction
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                        .clickable { onSelectTransaction(tx) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.description, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${tx.categoryLabel()} · ${tx.paymentMethod} · ${tx.transactionDate} ${tx.transactionTime}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val sign = if (tx.type == TransactionType.EXPENSE) "-" else "+"
                        val color = if (tx.type == TransactionType.EXPENSE) SmartColors.ExpenseOrange else SmartColors.IncomeMint
                        Text("$sign$currency${"%,.2f".format(tx.amount)}", color = color, fontWeight = FontWeight.Bold)
                        Text("Bal: $currency${"%,.2f".format(entry.runningBalanceAfter)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
