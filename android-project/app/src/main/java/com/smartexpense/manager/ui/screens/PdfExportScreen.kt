package com.smartexpense.manager.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.smartexpense.manager.ui.theme.SmartColors
import java.io.File
import java.time.LocalDate

@Composable
fun PdfExportScreen(
    onExportPdf: (startDate: String, endDate: String) -> File?
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var startDate by remember { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var endDate by remember { mutableStateOf(today.toString()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Export PDF Statement", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "Includes App Name, Account Holder, Date Range, Opening Balance, Total Income, Total Expenses, Closing Balance, and Running Balance Ledger.",
            color = SmartColors.TextMuted
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = {
                    startDate = today.minusDays(7).toString()
                    endDate = today.toString()
                },
                label = { Text("Weekly") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    startDate = today.withDayOfMonth(1).toString()
                    endDate = today.toString()
                },
                label = { Text("Monthly") }
            )
        }

        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("Start Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = endDate, onValueChange = { endDate = it }, label = { Text("End Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                val file = onExportPdf(startDate, endDate)
                if (file != null) {
                    statusMessage = "Generated: ${file.name}"
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share PDF Statement"))
                } else {
                    statusMessage = "Unable to generate PDF."
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
        ) {
            Text("Generate & Share PDF Statement", fontWeight = FontWeight.Bold)
        }

        statusMessage?.let { Text(it, color = SmartColors.IncomeMint) }
    }
}
