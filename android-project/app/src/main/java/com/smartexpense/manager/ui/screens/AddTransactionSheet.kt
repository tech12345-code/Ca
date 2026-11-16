package com.smartexpense.manager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.Transaction
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.ui.theme.SmartColors
import java.time.LocalDate
import java.time.LocalTime

private val CONTEXTUAL_PAYMENT_METHODS = listOf(
    "UPI",
    "Google Pay",
    "PhonePe",
    "Paytm",
    "Card",
    "Cash"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTransactionSheet(
    initialType: TransactionType,
    presetCategory: ExpenseCategory? = null,
    existing: Transaction?,
    onDismiss: () -> Unit,
    onSave: (
        type: TransactionType,
        amount: Double,
        description: String,
        merchant: String,
        category: ExpenseCategory,
        customCategoryDetail: String,
        paymentMethod: String,
        sourceOfMoney: String,
        note: String,
        date: String,
        time: String
    ) -> Unit
) {
    var txType by remember { mutableStateOf(existing?.type ?: initialType) }
    var amountText by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var merchant by remember { mutableStateOf(existing?.merchant ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: presetCategory ?: ExpenseCategory.Food) }
    var customCategoryDetail by remember {
        mutableStateOf(
            if (existing?.category == ExpenseCategory.Other) {
                existing.customCategoryDetail.ifBlank { existing.description }
            } else {
                ""
            }
        )
    }
    var paymentMethod by remember { mutableStateOf(existing?.paymentMethod ?: "UPI") }
    var sourceOfMoney by remember { mutableStateOf(existing?.sourceOfMoney ?: "Salary / Bank") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember { mutableStateOf(existing?.transactionDate ?: LocalDate.now().toString()) }
    var time by remember {
        mutableStateOf(
            existing?.transactionTime ?: "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when {
                    existing != null -> "Edit Transaction"
                    txType == TransactionType.EXPENSE -> "Add Expense"
                    else -> "Add Balance"
                },
                style = MaterialTheme.typography.titleLarge
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = txType == TransactionType.EXPENSE,
                    onClick = {
                        txType = TransactionType.EXPENSE
                        error = null
                    },
                    label = { Text("Expense") }
                )
                FilterChip(
                    selected = txType == TransactionType.INCOME,
                    onClick = {
                        txType = TransactionType.INCOME
                        customCategoryDetail = ""
                        error = null
                    },
                    label = { Text("Add Balance") }
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = {
                    Text(
                        if (txType == TransactionType.EXPENSE && category == ExpenseCategory.Other) {
                            "Description (Optional if specified below)"
                        } else {
                            "Description"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (txType == TransactionType.EXPENSE) {
                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCategory.expenseCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                if (cat != ExpenseCategory.Other) {
                                    customCategoryDetail = ""
                                }
                                category = cat
                                error = null
                            },
                            label = { Text(cat.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }

                AnimatedVisibility(visible = category == ExpenseCategory.Other) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SmartColors.Background, RoundedCornerShape(14.dp))
                            .border(1.dp, SmartColors.NeonLime.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Where have you spent this money? *",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.NeonLime,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = customCategoryDetail,
                            onValueChange = {
                                customCategoryDetail = it.take(120)
                                error = null
                            },
                            placeholder = {
                                Text("Enter expense details (e.g. Mobile recharge, Movie ticket, Gift...)")
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SmartColors.NeonLime,
                                unfocusedBorderColor = SmartColors.NeonLime.copy(alpha = 0.4f),
                                focusedLabelColor = SmartColors.NeonLime,
                                cursorColor = SmartColors.NeonLime
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text("Payment Method", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CONTEXTUAL_PAYMENT_METHODS.forEach { methodOption ->
                        FilterChip(
                            selected = paymentMethod.equals(methodOption, ignoreCase = true),
                            onClick = { paymentMethod = methodOption },
                            label = { Text(methodOption) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = paymentMethod,
                    onValueChange = { paymentMethod = it },
                    label = { Text("Payment Method (UPI, Google Pay, PhonePe, Paytm, Card, Cash)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = sourceOfMoney,
                    onValueChange = { sourceOfMoney = it },
                    label = { Text("Source of Money (Salary, Freelance, Refund)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Time (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Optional Note") },
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(error!!, color = SmartColors.DangerRed)
            }

            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull()
                    if (amount == null || amount <= 0.0 || amount.isNaN()) {
                        error = "Enter a valid positive amount."
                        return@Button
                    }
                    val trimmedCustomDetail =
                        if (txType == TransactionType.EXPENSE && category == ExpenseCategory.Other) {
                            customCategoryDetail.trim()
                        } else {
                            ""
                        }
                    if (txType == TransactionType.EXPENSE && category == ExpenseCategory.Other && trimmedCustomDetail.isBlank()) {
                        error = "Please enter where you spent this money for the Other category."
                        return@Button
                    }
                    val resolvedDescription = description.trim().ifBlank { trimmedCustomDetail }
                    if (resolvedDescription.isBlank()) {
                        error = "Description cannot be empty."
                        return@Button
                    }
                    onSave(
                        txType,
                        amount,
                        resolvedDescription,
                        merchant.ifBlank { resolvedDescription },
                        category,
                        trimmedCustomDetail,
                        paymentMethod,
                        sourceOfMoney,
                        note,
                        date,
                        time
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (txType == TransactionType.EXPENSE) SmartColors.ExpenseOrange else SmartColors.NeonLime,
                    contentColor = if (txType == TransactionType.EXPENSE) MaterialTheme.colorScheme.onSecondary else SmartColors.Background
                )
            ) {
                Text("Save Transaction", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
