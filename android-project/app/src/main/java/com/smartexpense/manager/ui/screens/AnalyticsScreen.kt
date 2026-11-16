package com.smartexpense.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.ui.components.DynamicMonthlyBudgetSection
import com.smartexpense.manager.ui.components.budgetStatusColorValue
import com.smartexpense.manager.ui.theme.SmartColors

@Composable
fun AnalyticsScreen(
    currency: String,
    summary: BalanceSummary,
    onOpenPdfExport: () -> Unit
) {
    val expenses = summary.ledger.map { it.transaction }.filter { it.type == TransactionType.EXPENSE }
    val categoryTotals = expenses.groupBy { it.category }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .toList()
        .sortedByDescending { it.second }
    val topCategory = categoryTotals.firstOrNull()
    val maxCategorySpend = categoryTotals.maxOfOrNull { it.second } ?: 1.0
    val otherCustomTotals = expenses
        .filter { it.category == ExpenseCategory.Other }
        .groupBy { it.customCategoryDetail.trim().ifBlank { it.description.trim().ifBlank { "Other" } } }
        .map { (label, list) -> Triple(label, list.sumOf { it.amount }, list.size) }
        .sortedByDescending { it.second }
    val maxOtherSpend = otherCustomTotals.maxOfOrNull { it.second } ?: 1.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Analytics & Reports", style = MaterialTheme.typography.headlineLarge)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    label = "Top Category",
                    value = topCategory?.first?.displayName ?: "None",
                    sub = if (topCategory != null) "$currency${"%,.0f".format(topCategory.second)}" else "-",
                    valueColor = SmartColors.NeonLime,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Budget Used",
                    value = "${"%.1f".format(summary.budgetUsagePercent)}%",
                    sub = "$currency${"%,.0f".format(summary.monthlySpent)} / $currency${"%,.0f".format(summary.monthlyBudget)}",
                    valueColor = budgetStatusColorValue(summary.budgetStatusColor),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                DynamicMonthlyBudgetSection(currency = currency, summary = summary)
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Income vs Expenses", style = MaterialTheme.typography.titleMedium)
                val totalFlow = (summary.totalIncome + summary.totalExpenses).coerceAtLeast(1.0)
                Text("Income: +$currency${"%,.2f".format(summary.totalIncome)}", color = SmartColors.IncomeMint)
                LinearProgressIndicator(
                    progress = { (summary.totalIncome / totalFlow).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = SmartColors.IncomeMint,
                    trackColor = SmartColors.SurfaceElevated
                )
                Text("Expenses: -$currency${"%,.2f".format(summary.totalExpenses)}", color = SmartColors.ExpenseOrange)
                LinearProgressIndicator(
                    progress = { (summary.totalExpenses / totalFlow).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = SmartColors.ExpenseOrange,
                    trackColor = SmartColors.SurfaceElevated
                )
            }
        }

        item {
            Text("Category-Wise Spending", style = MaterialTheme.typography.titleMedium)
        }

        items(categoryTotals) { (cat, total) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cat.displayName, fontWeight = FontWeight.Medium)
                    Text("$currency${"%,.2f".format(total)}", color = SmartColors.ExpenseOrange, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth((total / maxCategorySpend).toFloat().coerceIn(0.05f, 1f))
                        .height(6.dp)
                        .background(SmartColors.NeonLime, RoundedCornerShape(3.dp))
                )
            }
        }

        if (otherCustomTotals.isNotEmpty()) {
            item {
                Text("Other Category — Custom Spend Breakdown", style = MaterialTheme.typography.titleMedium)
            }
            items(otherCustomTotals) { (label, total, count) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "Other — $label ($count)",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("$currency${"%,.2f".format(total)}", color = SmartColors.ExpenseOrange, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((total / maxOtherSpend).toFloat().coerceIn(0.05f, 1f))
                            .height(6.dp)
                            .background(SmartColors.ExpenseOrange, RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        item {
            Button(
                onClick = onOpenPdfExport,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
            ) {
                Text("Export PDF Financial Statement", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    sub: String,
    valueColor: androidx.compose.ui.graphics.Color = SmartColors.NeonLime,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SmartColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, SmartColors.Border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
        Text(sub, style = MaterialTheme.typography.labelMedium)
    }
}
