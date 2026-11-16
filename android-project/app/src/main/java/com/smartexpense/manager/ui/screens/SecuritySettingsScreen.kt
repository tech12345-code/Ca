package com.smartexpense.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartexpense.manager.domain.model.AutoLockTimeout
import com.smartexpense.manager.domain.model.BiometricCapability
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.domain.model.SecuritySettings
import com.smartexpense.manager.ui.theme.SmartColors

@Composable
fun SecuritySettingsScreen(
    userEmail: String,
    settings: SecuritySettings,
    biometricCapability: BiometricCapability,
    onBack: () -> Unit,
    onToggleAppLock: (Boolean) -> Result<Unit>,
    onSelectMethod: (SecurityMethod) -> Result<Unit>,
    onChangeSemPin: (currentPin: String, newPin: String, confirmPin: String) -> Result<Unit>,
    onCreateSemPin: (newPin: String, confirmPin: String) -> Result<Unit>,
    onToggleFingerprintQuickUnlock: (Boolean) -> Unit,
    onSelectTimeout: (AutoLockTimeout) -> Unit,
    onToggleLockOnClose: (Boolean) -> Unit,
    onLockNow: () -> Unit
) {
    var showCreateOrChangePinDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = SmartColors.TextPrimary)
                }
                Column {
                    Text("SEM App Lock & Security", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Android Keystore AES256-GCM · Local device protection",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartColors.TextMuted
                    )
                }
            }
        }

        // Master App Lock Switch
        item {
            SecurityCard("App Lock Status") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.appLockEnabled) "App Lock Active" else "App Lock Disabled",
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.appLockEnabled) SmartColors.IncomeMint else SmartColors.ExpenseOrange
                        )
                        Text(
                            text = "Current method: ${settings.method.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.TextMuted
                        )
                    }
                    Switch(
                        checked = settings.appLockEnabled,
                        onCheckedChange = { enable ->
                            val res = onToggleAppLock(enable)
                            if (res.isFailure && enable && !settings.hasSemPinSet) {
                                showCreateOrChangePinDialog = true
                            }
                        }
                    )
                }
                if (settings.appLockEnabled && settings.method != SecurityMethod.NONE) {
                    OutlinedButton(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = SmartColors.NeonLime)
                        Text("  Lock App Now", color = SmartColors.NeonLime, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Primary Security Method Selector (single primary method active at a time)
        item {
            SecurityCard("Primary Security Method") {
                Text(
                    text = "Only one primary security method is active at a time. SEM-PIN is never synced to Firebase Firestore or logged.",
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartColors.TextMuted
                )
                SecurityMethod.entries.forEach { method ->
                    val selected = settings.method == method
                    val available = method != SecurityMethod.DEVICE_LOCK || biometricCapability.anyDeviceSecurityAvailable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) SmartColors.SurfaceElevated else SmartColors.Background,
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) SmartColors.NeonLime else SmartColors.Border,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = available) {
                                if (method == SecurityMethod.SEM_PIN && !settings.hasSemPinSet) {
                                    showCreateOrChangePinDialog = true
                                } else {
                                    onSelectMethod(method)
                                }
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = method.label,
                                fontWeight = FontWeight.SemiBold,
                                color = if (available) SmartColors.TextPrimary else SmartColors.TextMuted
                            )
                            Text(
                                text = if (!available) biometricCapability.statusDescription else method.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = SmartColors.TextMuted
                            )
                        }
                        Icon(
                            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) SmartColors.NeonLime else SmartColors.TextMuted
                        )
                    }
                }
            }
        }

        // SEM-PIN Management & Fingerprint Optional Fallback
        item {
            SecurityCard("SEM-PIN & Biometrics") {
                Button(
                    onClick = { showCreateOrChangePinDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmartColors.NeonLime,
                        contentColor = SmartColors.Background
                    )
                ) {
                    Text(
                        text = if (settings.hasSemPinSet) "Change SEM-PIN" else "Create 4–6 Digit SEM-PIN",
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint Quick Unlock", fontWeight = FontWeight.Medium)
                        Text(
                            text = if (biometricCapability.strongBiometricAvailable) {
                                "Allow fingerprint prompt alongside SEM-PIN."
                            } else {
                                "Strong biometric sensor not enrolled on this device."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.TextMuted
                        )
                    }
                    Switch(
                        checked = settings.fingerprintQuickUnlockEnabled,
                        enabled = biometricCapability.strongBiometricAvailable && settings.method == SecurityMethod.SEM_PIN,
                        onCheckedChange = onToggleFingerprintQuickUnlock
                    )
                }
            }
        }

        // Auto-Lock Timeout & Close Behavior
        item {
            SecurityCard("Auto-Lock Timing & Privacy") {
                Text("Re-require authentication after leaving app:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AutoLockTimeout.entries.forEach { option ->
                        FilterChip(
                            selected = settings.autoLockTimeout == option,
                            onClick = { onSelectTimeout(option) },
                            label = {
                                Text(
                                    text = when (option) {
                                        AutoLockTimeout.IMMEDIATE -> "Now"
                                        AutoLockTimeout.SECONDS_30 -> "30s"
                                        AutoLockTimeout.MINUTE_1 -> "1m"
                                        AutoLockTimeout.MINUTES_5 -> "5m"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SmartColors.NeonLime,
                                selectedLabelColor = SmartColors.Background
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock immediately when app closes", fontWeight = FontWeight.Medium)
                        Text(
                            text = "Cold app launches always require verification first.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.TextMuted
                        )
                    }
                    Switch(
                        checked = settings.lockOnAppClose,
                        onCheckedChange = onToggleLockOnClose
                    )
                }

                Text(
                    text = "FLAG_SECURE is active whenever App Lock is ON to prevent recent-apps previews from exposing balances.",
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartColors.IncomeMint
                )
            }
        }

        // Recovery Information
        item {
            SecurityCard("Recovery Options") {
                Text(
                    text = "If you forget your SEM-PIN, tap \"Forgot SEM-PIN?\" on the lock screen to verify your identity using Google account re-authentication ($userEmail) or your device screen lock before creating a new PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartColors.TextMuted
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showCreateOrChangePinDialog) {
        CreateOrChangePinDialog(
            hasExistingPin = settings.hasSemPinSet,
            onDismiss = { showCreateOrChangePinDialog = false },
            onSave = { currentPin, newPin, confirmPin ->
                if (settings.hasSemPinSet) {
                    onChangeSemPin(currentPin, newPin, confirmPin)
                } else {
                    onCreateSemPin(newPin, confirmPin)
                }
            }
        )
    }
}

@Composable
private fun SecurityCard(title: String, content: @Composable () -> Unit) {
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
private fun CreateOrChangePinDialog(
    hasExistingPin: Boolean,
    onDismiss: () -> Unit,
    onSave: (currentPin: String, newPin: String, confirmPin: String) -> Result<Unit>
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        title = { Text(if (hasExistingPin) "Change SEM-PIN" else "Create SEM-PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasExistingPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) currentPin = it },
                        label = { Text("Current SEM-PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it },
                    label = { Text("New 4–6 digit SEM-PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPin = it },
                    label = { Text("Confirm SEM-PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Text(errorText!!, color = SmartColors.DangerRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = onSave(currentPin, newPin, confirmPin)
                    res.onSuccess { onDismiss() }
                        .onFailure { err -> errorText = err.message }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartColors.NeonLime,
                    contentColor = SmartColors.Background
                )
            ) { Text("Save PIN", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
