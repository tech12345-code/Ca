package com.smartexpense.manager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.BalanceSummary
import com.smartexpense.manager.domain.model.BalanceValidationResult
import com.smartexpense.manager.domain.model.BudgetStatusColor
import com.smartexpense.manager.ui.theme.SmartColors

@Composable
fun budgetStatusColorValue(status: BudgetStatusColor): Color = when (status) {
    BudgetStatusColor.GREEN -> SmartColors.IncomeMint
    BudgetStatusColor.YELLOW -> SmartColors.WarningYellow
    BudgetStatusColor.RED -> SmartColors.DangerRed
}

@Composable
fun DynamicMonthlyBudgetSection(
    currency: String,
    summary: BalanceSummary,
    modifier: Modifier = Modifier
) {
    val targetColor = budgetStatusColorValue(summary.budgetStatusColor)
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 350),
        label = "budget_bar_color"
    )
    val targetProgress = if (summary.monthlyBudget > 0.0) {
        (summary.budgetUsagePercent / 100.0).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 350),
        label = "budget_bar_progress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monthly Budget Usage",
                style = MaterialTheme.typography.labelMedium,
                color = SmartColors.TextMuted
            )
            Text(
                text = "${"%.1f".format(summary.budgetUsagePercent)}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = animatedColor
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = animatedColor,
                trackColor = SmartColors.SurfaceElevated
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Spent", style = MaterialTheme.typography.labelMedium, color = SmartColors.TextMuted)
                Text(
                    "$currency${"%,.2f".format(summary.monthlySpent)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SmartColors.ExpenseOrange
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Budget", style = MaterialTheme.typography.labelMedium, color = SmartColors.TextMuted)
                Text(
                    "$currency${"%,.2f".format(summary.monthlyBudget)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SmartColors.TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Remaining", style = MaterialTheme.typography.labelMedium, color = SmartColors.TextMuted)
                Text(
                    "$currency${"%,.2f".format(summary.remainingBudget)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (summary.remainingBudget < 0) SmartColors.DangerRed else SmartColors.IncomeMint
                )
            }
        }

        if (summary.isBudgetExceeded) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.DangerRed.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                    .border(1.dp, SmartColors.DangerRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = SmartColors.DangerRed,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Monthly Budget Exceeded!",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = SmartColors.DangerRed
                )
            }
        }
    }
}

@Composable
fun BalanceExhaustedBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SmartColors.ExpenseOrange.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .border(1.dp, SmartColors.ExpenseOrange.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = SmartColors.ExpenseOrange,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = "Balance Exhausted!",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = SmartColors.ExpenseOrange
            )
            Text(
                text = "You have used your entire available balance.",
                style = MaterialTheme.typography.labelMedium,
                color = SmartColors.TextPrimary
            )
        }
    }
}

@Composable
fun InsufficientBalanceDialog(
    currency: String,
    validation: BalanceValidationResult,
    onAddBalance: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = SmartColors.DangerRed,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = validation.errorTitle ?: "Insufficient Balance!",
                style = MaterialTheme.typography.titleLarge,
                color = SmartColors.DangerRed,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = validation.errorMessage ?: "You don't have enough money to complete this expense.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartColors.TextPrimary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartColors.Background, RoundedCornerShape(14.dp))
                        .border(1.dp, SmartColors.Border, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Current Available Balance", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "$currency${"%,.2f".format(validation.currentAvailableBalance)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = SmartColors.NeonLime
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Additional Amount Required", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "$currency${"%,.2f".format(validation.shortfallAmount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = SmartColors.DangerRed
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAddBalance,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartColors.NeonLime,
                    contentColor = SmartColors.Background
                )
            ) {
                Text("Add Balance", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Adjust Amount", color = SmartColors.TextPrimary)
            }
        }
    )
}

@Composable
fun BalanceExhaustedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        shape = RoundedCornerShape(22.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = SmartColors.ExpenseOrange,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Balance Exhausted!",
                style = MaterialTheme.typography.titleLarge,
                color = SmartColors.ExpenseOrange,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "You have used your entire available balance.",
                style = MaterialTheme.typography.bodyMedium,
                color = SmartColors.TextPrimary
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartColors.NeonLime,
                    contentColor = SmartColors.Background
                )
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        }
    )
}
