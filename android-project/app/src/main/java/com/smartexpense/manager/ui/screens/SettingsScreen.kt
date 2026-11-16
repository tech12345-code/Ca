package com.smartexpense.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.NotificationPreferences
import com.smartexpense.manager.domain.model.SecuritySettings
import com.smartexpense.manager.domain.model.UserProfile
import com.smartexpense.manager.ui.theme.SmartColors

@Composable
fun SettingsScreen(
    profile: UserProfile,
    securitySettings: SecuritySettings,
    onUpdateBudget: (Double) -> Unit,
    onUpdatePreferences: (NotificationPreferences) -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenPdfExport: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreCloud: () -> Unit,
    onSignOut: () -> Unit
) {
    var budgetText by remember(profile.monthlyBudget) { mutableStateOf(profile.monthlyBudget.toString()) }
    val prefs = profile.preferences

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
        }

        // Profile Card
        item {
            SectionCard("User Profile") {
                Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                Text(profile.email, color = SmartColors.TextMuted)
            }
        }

        // SEM App Lock Security
        item {
            SectionCard("SEM App Lock Security") {
                Text(
                    text = if (securitySettings.appLockEnabled) {
                        "Protected via ${securitySettings.method.label} · Auto-lock: ${securitySettings.autoLockTimeout.label}"
                    } else {
                        "App Lock is currently OFF"
                    },
                    color = if (securitySettings.appLockEnabled) SmartColors.IncomeMint else SmartColors.ExpenseOrange
                )
                Button(
                    onClick = onOpenSecuritySettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmartColors.NeonLime,
                        contentColor = SmartColors.Background
                    )
                ) {
                    Text("Manage SEM-PIN & App Lock", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Monthly Budget
        item {
            SectionCard("Monthly Budget Settings") {
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("Monthly Budget (${profile.currencySymbol})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { budgetText.toDoubleOrNull()?.let(onUpdateBudget) },
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.NeonLime, contentColor = SmartColors.Background)
                ) { Text("Update Budget", fontWeight = FontWeight.Bold) }
            }
        }

        // Push Notifications — FCM Controls
        item {
            SectionCard("Push Notifications (FCM)") {
                ToggleRow("Expense reminders", prefs.expenseReminders) {
                    onUpdatePreferences(prefs.copy(expenseReminders = it))
                }
                ToggleRow("Transaction confirmations", prefs.transactionConfirmations) {
                    onUpdatePreferences(prefs.copy(transactionConfirmations = it))
                }
                ToggleRow("Budget alerts (50%, 75%, 90%, 100%)", prefs.budgetAlerts) {
                    onUpdatePreferences(prefs.copy(budgetAlerts = it))
                }
                ToggleRow("Daily summaries", prefs.dailySummary) {
                    onUpdatePreferences(prefs.copy(dailySummary = it))
                }
                ToggleRow("Weekly reports", prefs.weeklyReport) {
                    onUpdatePreferences(prefs.copy(weeklyReport = it))
                }
                ToggleRow("Cloud backup alerts", prefs.cloudBackupAlerts) {
                    onUpdatePreferences(prefs.copy(cloudBackupAlerts = it))
                }
                ToggleRow("Quiet hours (${prefs.quietStartHour}:00 - ${prefs.quietEndHour}:00)", prefs.quietHoursEnabled) {
                    onUpdatePreferences(prefs.copy(quietHoursEnabled = it))
                }
            }
        }

        // Cloud Backup & Export
        item {
            SectionCard("Cloud Backup & Statement Export") {
                OutlinedButton(onClick = onBackupNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Cloud Backup Now")
                }
                OutlinedButton(onClick = onRestoreCloud, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore Backup from Firestore")
                }
                OutlinedButton(onClick = onOpenPdfExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Export PDF Statement")
                }
            }
        }

        // Privacy, About & Logout
        item {
            SectionCard("Privacy Policy & About") {
                Text("Smart Expense Manager v1.0.0", fontWeight = FontWeight.SemiBold)
                Text(
                    "All financial records are isolated per authenticated Firebase user. SEM-PIN is stored solely on this device with Android Keystore AES256-GCM encryption and never synced to Firestore.",
                    style = MaterialTheme.typography.labelMedium
                )
                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SmartColors.DangerRed)
                ) { Text("Logout") }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SmartColors.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, SmartColors.Border, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SmartColors.NeonLime)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
