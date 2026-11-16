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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.FragmentActivity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.smartexpense.manager.R
import com.smartexpense.manager.domain.model.BiometricCapability
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.ui.theme.SmartColors
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    isSignedIn: Boolean,
    budgetSetupCompleted: Boolean,
    biometricCapability: BiometricCapability,
    onGoogleIdToken: (String, String?, String?) -> Unit,
    onCompleteBudgetSetup: (Double) -> Unit,
    onCompleteSecuritySetup: (
        method: SecurityMethod,
        pin: String,
        confirmPin: String,
        enableFingerprintQuickUnlock: Boolean
    ) -> Result<Unit>,
    onTriggerDeviceLockTest: (
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var budgetText by remember { mutableStateOf("") }
    var selectedSecurity by remember { mutableStateOf(SecurityMethod.SEM_PIN) }
    var pinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var enableFingerprintQuick by remember { mutableStateOf(biometricCapability.strongBiometricAvailable) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val webClientId = stringResource(R.string.default_web_client_id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Smart Expense Manager",
            style = MaterialTheme.typography.headlineLarge,
            color = SmartColors.NeonLime
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Offline-first Room ledger with real-time chronological balance tracking, receipt OCR, split expenses, and Keystore app security.",
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.TextMuted
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SmartColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, SmartColors.Border, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                !isSignedIn -> {
                    Text(
                        text = "Step 1 · Sign in with Google",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Isolates your ledger and cloud backups securely under your Google account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SmartColors.TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val cm = CredentialManager.create(context)
                                    val googleIdOption = GetGoogleIdOption.Builder()
                                        .setFilterByAuthorizedAccounts(false)
                                        .setServerClientId(webClientId)
                                        .build()
                                    val request = GetCredentialRequest.Builder()
                                        .addCredentialOption(googleIdOption)
                                        .build()
                                    val result = cm.getCredential(context, request)
                                    val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
                                    onGoogleIdToken(cred.idToken, cred.displayName, cred.id)
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Unable to complete Google sign-in."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        )
                    ) {
                        Text("Continue with Google", fontWeight = FontWeight.Bold)
                    }
                }

                !budgetSetupCompleted -> {
                    Text(
                        text = "Step 2 · Set Your Monthly Budget",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "This amount is saved as your monthly spending budget and your initial opening balance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SmartColors.TextMuted
                    )
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it },
                        label = { Text("Monthly Budget / Opening Balance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val parsed = budgetText.toDoubleOrNull()
                            if (parsed == null || parsed < 0.0) {
                                errorMessage = "Please enter a valid positive amount."
                            } else {
                                errorMessage = null
                                onCompleteBudgetSetup(parsed)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        )
                    ) {
                        Text("Save Budget & Continue", fontWeight = FontWeight.Bold)
                    }
                }

                else -> {
                    Text(
                        text = "Step 3 · Protect Your Financial Ledger",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Choose how Smart Expense Manager should lock when closed or inactive. Your SEM-PIN is encrypted locally via Android Keystore and never uploaded to the cloud.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SmartColors.TextMuted
                    )

                    SecurityMethod.entries.forEach { method ->
                        val isSelected = selectedSecurity == method
                        val isAvailable = method != SecurityMethod.DEVICE_LOCK || biometricCapability.anyDeviceSecurityAvailable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) SmartColors.SurfaceElevated else SmartColors.Background,
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) SmartColors.NeonLime else SmartColors.Border,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable(enabled = isAvailable) {
                                    errorMessage = null
                                    selectedSecurity = method
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = method.label,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAvailable) SmartColors.TextPrimary else SmartColors.TextMuted
                                )
                                Text(
                                    text = if (!isAvailable) biometricCapability.statusDescription else method.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SmartColors.TextMuted
                                )
                            }
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) SmartColors.NeonLime else SmartColors.TextMuted
                            )
                        }
                    }

                    if (selectedSecurity == SecurityMethod.SEM_PIN) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                            label = { Text("Create 4–6 digit SEM-PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPinInput,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPinInput = it },
                            label = { Text("Re-enter SEM-PIN to confirm") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (biometricCapability.strongBiometricAvailable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = enableFingerprintQuick,
                                    onCheckedChange = { enableFingerprintQuick = it },
                                    colors = CheckboxDefaults.colors(checkedColor = SmartColors.NeonLime)
                                )
                                Text(
                                    text = "Also allow Fingerprint unlock on this device",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    if (selectedSecurity == SecurityMethod.NONE) {
                        Text(
                            text = "Warning: Without App Lock, anyone with access to your unlocked phone can view your expense ledger immediately.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartColors.ExpenseOrange
                        )
                    }

                    Button(
                        onClick = {
                            errorMessage = null
                            if (selectedSecurity == SecurityMethod.DEVICE_LOCK) {
                                if (activity == null) {
                                    errorMessage = "Device screen lock prompt unavailable right now."
                                    return@Button
                                }
                                onTriggerDeviceLockTest(
                                    activity,
                                    {
                                        onCompleteSecuritySetup(SecurityMethod.DEVICE_LOCK, "", "", true)
                                            .onFailure { err -> errorMessage = err.message }
                                    },
                                    { err -> errorMessage = err }
                                )
                            } else {
                                onCompleteSecuritySetup(
                                    selectedSecurity,
                                    pinInput,
                                    confirmPinInput,
                                    enableFingerprintQuick
                                ).onFailure { err ->
                                    errorMessage = err.message ?: "Could not save security configuration."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        )
                    ) {
                        Text(
                            text = when (selectedSecurity) {
                                SecurityMethod.SEM_PIN -> "Activate SEM-PIN & Open Dashboard"
                                SecurityMethod.DEVICE_LOCK -> "Verify Phone Lock & Open Dashboard"
                                SecurityMethod.NONE -> "Continue Without App Lock"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Text(errorMessage!!, color = SmartColors.DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
