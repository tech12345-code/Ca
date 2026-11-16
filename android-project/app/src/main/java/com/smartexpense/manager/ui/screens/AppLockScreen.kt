package com.smartexpense.manager.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.FragmentActivity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.smartexpense.manager.R
import com.smartexpense.manager.domain.model.BiometricCapability
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.domain.model.SecuritySettings
import com.smartexpense.manager.domain.security.PinSecurityEngine
import com.smartexpense.manager.ui.theme.SmartColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppLockScreen(
    userEmail: String,
    settings: SecuritySettings,
    biometricCapability: BiometricCapability,
    onVerifyPin: (String) -> Result<Unit>,
    onTriggerBiometricUnlock: (
        activity: FragmentActivity,
        allowDeviceCredential: Boolean,
        onError: (String) -> Unit
    ) -> Unit,
    onResetPinWithGoogleReauth: (
        idToken: String,
        newPin: String,
        confirmPin: String,
        onResult: (Result<Unit>) -> Unit
    ) -> Unit,
    onResetPinAfterDeviceAuth: (newPin: String, confirmPin: String) -> Result<Unit>
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val webClientId = stringResource(R.string.default_web_client_id)

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSec by remember(settings.lockoutUntilMillis) {
        mutableIntStateOf(
            PinSecurityEngine.remainingLockoutSeconds(
                settings.lockoutUntilMillis,
                System.currentTimeMillis()
            )
        )
    }

    var showForgotPinDialog by remember { mutableStateOf(false) }

    // Live countdown ticker whenever failed attempts trigger backoff
    LaunchedEffect(settings.lockoutUntilMillis) {
        while (true) {
            val rem = PinSecurityEngine.remainingLockoutSeconds(
                settings.lockoutUntilMillis,
                System.currentTimeMillis()
            )
            lockoutRemainingSec = rem
            if (rem <= 0) break
            delay(1000L)
        }
    }

    // Auto-launch official BiometricPrompt on screen entry when DEVICE_LOCK or quick unlock enabled
    LaunchedEffect(settings.method, settings.fingerprintQuickUnlockEnabled) {
        if (activity == null) return@LaunchedEffect
        when {
            settings.method == SecurityMethod.DEVICE_LOCK -> {
                onTriggerBiometricUnlock(activity, true) { err -> errorMessage = err }
            }
            settings.method == SecurityMethod.SEM_PIN &&
                settings.fingerprintQuickUnlockEnabled &&
                biometricCapability.strongBiometricAvailable -> {
                onTriggerBiometricUnlock(activity, false) { err -> errorMessage = err }
            }
        }
    }

    fun submitPin(pin: String) {
        if (lockoutRemainingSec > 0) {
            errorMessage = "Too many attempts. Retry in ${lockoutRemainingSec}s."
            return
        }
        val res = onVerifyPin(pin)
        if (res.isFailure) {
            errorMessage = res.exceptionOrNull()?.message ?: "Incorrect SEM-PIN."
            enteredPin = ""
        } else {
            errorMessage = null
            enteredPin = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartColors.Background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(SmartColors.SurfaceElevated, CircleShape)
                    .border(1.dp, SmartColors.NeonLime.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Smart Expense Manager Security",
                    tint = SmartColors.NeonLime,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Smart Expense Manager",
                style = MaterialTheme.typography.titleLarge,
                color = SmartColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            if (userEmail.isNotBlank()) {
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartColors.TextMuted
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (settings.method == SecurityMethod.DEVICE_LOCK) {
                    "Unlock with Phone Screen Lock"
                } else {
                    "Enter SEM-PIN"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = SmartColors.TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (settings.method == SecurityMethod.DEVICE_LOCK) {
                    "Verify with Fingerprint, Face, or Device PIN/Pattern to view your balances."
                } else {
                    "Enter your 4–6 digit SEM-PIN to unlock your financial ledger."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SmartColors.TextMuted,
                textAlign = TextAlign.Center
            )
        }

        if (settings.method == SecurityMethod.DEVICE_LOCK) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(SmartColors.Surface)
                        .border(1.5.dp, SmartColors.NeonLime, CircleShape)
                        .clickable(enabled = activity != null) {
                            activity?.let { act ->
                                errorMessage = null
                                onTriggerBiometricUnlock(act, true) { err -> errorMessage = err }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = "Unlock with Biometrics",
                        tint = SmartColors.NeonLime,
                        modifier = Modifier.size(48.dp)
                    )
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = SmartColors.DangerRed,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {
                        activity?.let { act ->
                            errorMessage = null
                            onTriggerBiometricUnlock(act, true) { err -> errorMessage = err }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmartColors.NeonLime,
                        contentColor = SmartColors.Background
                    )
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null)
                    Text("  Verify Phone Screen Lock", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            // Google Pay-inspired numeric PIN dots + keypad
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (idx in 0 until 6) {
                        val filled = idx < enteredPin.length
                        val isOptionalSlot = idx >= 4
                        val dotColor by animateColorAsState(
                            targetValue = when {
                                filled -> SmartColors.NeonLime
                                isOptionalSlot -> SmartColors.Border.copy(alpha = 0.4f)
                                else -> SmartColors.Border
                            },
                            label = "pin_dot_$idx"
                        )
                        Box(
                            modifier = Modifier
                                .size(if (filled) 16.dp else 14.dp)
                                .background(
                                    color = if (filled) dotColor else SmartColors.Surface,
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = dotColor,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                if (lockoutRemainingSec > 0) {
                    Text(
                        text = "Too many incorrect attempts. Try again in ${lockoutRemainingSec}s.",
                        color = SmartColors.DangerRed,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = SmartColors.DangerRed,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "4–6 digits",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartColors.TextMuted
                    )
                }

                NumericPinKeypad(
                    enabled = lockoutRemainingSec == 0,
                    showBiometricKey = settings.fingerprintQuickUnlockEnabled && biometricCapability.strongBiometricAvailable,
                    canUnlock = enteredPin.length >= 4,
                    onDigit = { digit ->
                        if (enteredPin.length < 6) {
                            errorMessage = null
                            enteredPin += digit
                        }
                    },
                    onBackspace = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                        }
                    },
                    onBiometric = {
                        activity?.let { act ->
                            onTriggerBiometricUnlock(act, false) { err -> errorMessage = err }
                        }
                    },
                    onSubmit = { submitPin(enteredPin) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showForgotPinDialog = true }) {
                    Text("Forgot SEM-PIN?", color = SmartColors.NeonLime)
                }
                if (enteredPin.length in 4..6) {
                    Button(
                        onClick = { submitPin(enteredPin) },
                        enabled = lockoutRemainingSec == 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SmartColors.NeonLime,
                            contentColor = SmartColors.Background
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Unlock", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showForgotPinDialog) {
        ForgotPinRecoveryDialog(
            userEmail = userEmail,
            canUseDeviceCredential = biometricCapability.anyDeviceSecurityAvailable,
            onGoogleReauth = { newPin, confirmPin, callback ->
                scope.launch {
                    runCatching {
                        val cm = CredentialManager.create(context)
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(true)
                            .setServerClientId(webClientId)
                            .build()
                        val req = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()
                        val res = cm.getCredential(context, req)
                        val cred = GoogleIdTokenCredential.createFrom(res.credential.data)
                        if (userEmail.isNotBlank() && !cred.id.equals(userEmail, ignoreCase = true)) {
                            error("Signed-in account (${cred.id}) does not match $userEmail.")
                        }
                        onResetPinWithGoogleReauth(cred.idToken, newPin, confirmPin, callback)
                    }.onFailure { err ->
                        callback(Result.failure(err))
                    }
                }
            },
            onDeviceCredentialVerifyAndReset = { newPin, confirmPin, callback ->
                val act = activity
                if (act == null) {
                    callback(Result.failure(IllegalStateException("Activity unavailable for biometric verification.")))
                    return@ForgotPinRecoveryDialog
                }
                val checkFormat = PinSecurityEngine.validatePinSetup(newPin, confirmPin)
                if (checkFormat.isFailure) {
                    callback(checkFormat)
                    return@ForgotPinRecoveryDialog
                }
                onTriggerBiometricUnlock(act, true) { err ->
                    callback(Result.failure(IllegalStateException(err)))
                }
                // Wait: onTriggerBiometricUnlock invokes onSuccess in ViewModel which unlocks.
                // So we pass dedicated reset only when invoked via direct callback below.
            },
            onDirectBiometricReset = { newPin, confirmPin, callback ->
                val checkFormat = PinSecurityEngine.validatePinSetup(newPin, confirmPin)
                if (checkFormat.isFailure) {
                    callback(checkFormat)
                    return@ForgotPinRecoveryDialog
                }
                val res = onResetPinAfterDeviceAuth(newPin, confirmPin)
                callback(res)
            },
            onDismiss = { showForgotPinDialog = false }
        )
    }
}

@Composable
private fun NumericPinKeypad(
    enabled: Boolean,
    showBiometricKey: Boolean,
    canUnlock: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
    onSubmit: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { digit ->
                    KeypadButton(
                        label = digit,
                        enabled = enabled,
                        onClick = { onDigit(digit) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (showBiometricKey) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(SmartColors.Surface)
                        .border(1.dp, SmartColors.Border, CircleShape)
                        .clickable(enabled = enabled) { onBiometric() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fingerprint,
                        contentDescription = "Fingerprint Unlock",
                        tint = SmartColors.NeonLime,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (canUnlock && enabled) SmartColors.NeonLime.copy(alpha = 0.16f) else SmartColors.Surface)
                        .border(
                            1.dp,
                            if (canUnlock && enabled) SmartColors.NeonLime else SmartColors.Border,
                            CircleShape
                        )
                        .clickable(enabled = enabled && canUnlock) { onSubmit() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OK",
                        fontWeight = FontWeight.Bold,
                        color = if (canUnlock && enabled) SmartColors.NeonLime else SmartColors.TextMuted
                    )
                }
            }

            KeypadButton(
                label = "0",
                enabled = enabled,
                onClick = { onDigit("0") }
            )

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(SmartColors.Surface)
                    .border(1.dp, SmartColors.Border, CircleShape)
                    .clickable(enabled = enabled) { onBackspace() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = "Backspace",
                    tint = SmartColors.TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(SmartColors.Surface)
            .border(1.dp, SmartColors.Border, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) SmartColors.TextPrimary else SmartColors.TextMuted
        )
    }
}

@Composable
private fun ForgotPinRecoveryDialog(
    userEmail: String,
    canUseDeviceCredential: Boolean,
    onGoogleReauth: (newPin: String, confirmPin: String, (Result<Unit>) -> Unit) -> Unit,
    onDeviceCredentialVerifyAndReset: (newPin: String, confirmPin: String, (Result<Unit>) -> Unit) -> Unit,
    onDirectBiometricReset: (newPin: String, confirmPin: String, (Result<Unit>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmartColors.Surface,
        title = { Text("Reset Forgotten SEM-PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "For your security, resetting your SEM-PIN requires verified account re-authentication ($userEmail) or your device's screen lock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartColors.TextMuted
                )
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
                    label = { Text("Confirm New SEM-PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (recoveryError != null) {
                    Text(recoveryError!!, color = SmartColors.DangerRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !busy,
                    onClick = {
                        val formatRes = PinSecurityEngine.validatePinSetup(newPin, confirmPin)
                        if (formatRes.isFailure) {
                            recoveryError = formatRes.exceptionOrNull()?.message
                            return@Button
                        }
                        busy = true
                        recoveryError = null
                        onGoogleReauth(newPin, confirmPin) { res ->
                            busy = false
                            res.onSuccess { onDismiss() }
                                .onFailure { err -> recoveryError = err.message ?: "Google verification failed." }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmartColors.NeonLime,
                        contentColor = SmartColors.Background
                    )
                ) { Text("Verify Google Account & Reset PIN", fontWeight = FontWeight.Bold) }

                if (canUseDeviceCredential && activity != null) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            val formatRes = PinSecurityEngine.validatePinSetup(newPin, confirmPin)
                            if (formatRes.isFailure) {
                                recoveryError = formatRes.exceptionOrNull()?.message
                                return@OutlinedButton
                            }
                            busy = true
                            val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
                            val prompt = androidx.biometric.BiometricPrompt(
                                activity,
                                executor,
                                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                        onDirectBiometricReset(newPin, confirmPin) { res ->
                                            busy = false
                                            res.onSuccess { onDismiss() }
                                                .onFailure { err -> recoveryError = err.message }
                                        }
                                    }

                                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                        busy = false
                                        recoveryError = errString.toString()
                                    }
                                }
                            )
                            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                                .setTitle("Verify Device Identity")
                                .setSubtitle("Confirm device screen lock before resetting SEM-PIN")
                                .setAllowedAuthenticators(
                                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                )
                                .build()
                            prompt.authenticate(info)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Verify Phone Screen Lock & Reset") }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    )
}
