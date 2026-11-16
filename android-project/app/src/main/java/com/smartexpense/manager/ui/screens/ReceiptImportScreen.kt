package com.smartexpense.manager.ui.screens

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartexpense.manager.domain.model.EntrySource
import com.smartexpense.manager.domain.model.ExpenseCategory
import com.smartexpense.manager.domain.model.TransactionType
import com.smartexpense.manager.ui.theme.SmartColors
import com.smartexpense.manager.ui.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptImportScreen(
    viewModel: MainViewModel,
    currency: String,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val draft by viewModel.receiptDraft.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningReceipt.collectAsStateWithLifecycle()
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.scanReceiptUri(context, it) } }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.scanReceiptUri(context, it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraTempUri != null) {
            viewModel.scanReceiptUri(context, cameraTempUri!!)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File(context.filesDir, "receipts/scan_${System.currentTimeMillis()}.jpg").apply {
                parentFile?.mkdirs()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("IMPORT RECEIPT", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "Scan printed bills, digital UPI screenshots, or PDF receipts with Google ML Kit OCR.",
            color = SmartColors.TextMuted
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartColors.NeonLime,
                    contentColor = SmartColors.Background
                )
            ) { Text("Camera", fontWeight = FontWeight.Bold) }

            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) { Text("Gallery") }

            OutlinedButton(
                onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.weight(1f)
            ) { Text("PDF") }
        }

        if (isScanning) {
            CircularProgressIndicator(color = SmartColors.NeonLime)
        }

        draft?.let { extracted ->
            var merchant by remember(extracted) { mutableStateOf(extracted.merchantName) }
            var amount by remember(extracted) { mutableStateOf(extracted.amount) }
            var date by remember(extracted) { mutableStateOf(extracted.transactionDate) }
            var time by remember(extracted) { mutableStateOf(extracted.transactionTime) }
            var paymentMethod by remember(extracted) { mutableStateOf(extracted.paymentMethod) }
            var referenceId by remember(extracted) { mutableStateOf(extracted.referenceId) }
            var invoiceNumber by remember(extracted) { mutableStateOf(extracted.invoiceNumber) }
            var category by remember(extracted) { mutableStateOf(ExpenseCategory.Other) }
            var customCategoryDetail by remember(extracted) { mutableStateOf("") }
            var validationError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(extracted) {
                merchant = extracted.merchantName
                amount = extracted.amount
                date = extracted.transactionDate
                time = extracted.transactionTime
                paymentMethod = extracted.paymentMethod
                referenceId = extracted.referenceId
                invoiceNumber = extracted.invoiceNumber
                customCategoryDetail = ""
                validationError = null
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SmartColors.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Confirm Extracted Receipt Details", style = MaterialTheme.typography.titleMedium)

                if (extracted.isPossibleDuplicate && extracted.duplicateReason != null) {
                    Text(
                        text = "Duplicate Warning: ${extracted.duplicateReason}",
                        color = SmartColors.ExpenseOrange
                    )
                }
                if (extracted.missingFields.isNotEmpty()) {
                    Text(
                        text = "Missing or unclear fields detected (${extracted.missingFields.joinToString(", ")}). Please fill them in below:",
                        color = SmartColors.NeonLime,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("Merchant Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Time (HH:mm)") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = paymentMethod, onValueChange = { paymentMethod = it }, label = { Text("Payment Method") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = referenceId, onValueChange = { referenceId = it }, label = { Text("Transaction Reference ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = invoiceNumber, onValueChange = { invoiceNumber = it }, label = { Text("Invoice Number") }, modifier = Modifier.fillMaxWidth())

                Text("Select Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCategory.expenseCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                if (cat != ExpenseCategory.Other) {
                                    customCategoryDetail = ""
                                }
                                category = cat
                                validationError = null
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
                                validationError = null
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

                if (validationError != null) {
                    Text(validationError!!, color = SmartColors.DangerRed)
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val parsedAmount = amount.toDoubleOrNull()
                        if (merchant.isBlank() || parsedAmount == null || parsedAmount <= 0.0 || date.isBlank() || time.isBlank()) {
                            validationError = "Please confirm Merchant, positive Amount, Date, and Time before saving."
                            return@Button
                        }
                        val trimmedDetail =
                            if (category == ExpenseCategory.Other) customCategoryDetail.trim() else ""
                        if (category == ExpenseCategory.Other && trimmedDetail.isBlank()) {
                            validationError = "Please enter where you spent this money for the Other category."
                            return@Button
                        }
                        val result = viewModel.saveTransaction(
                            type = TransactionType.EXPENSE,
                            amount = parsedAmount,
                            description = merchant,
                            merchant = merchant,
                            category = category,
                            customCategoryDetail = trimmedDetail,
                            paymentMethod = paymentMethod.ifBlank { "UPI" },
                            sourceOfMoney = "",
                            note = "Imported via ML Kit OCR",
                            dateStr = date,
                            timeStr = time,
                            referenceId = referenceId,
                            invoiceNumber = invoiceNumber,
                            entrySource = EntrySource.RECEIPT_OCR
                        )
                        if (result.allowed) {
                            onSaved()
                        } else {
                            validationError = result.errorMessage
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
                ) {
                    Text("Confirm & Save Expense", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
