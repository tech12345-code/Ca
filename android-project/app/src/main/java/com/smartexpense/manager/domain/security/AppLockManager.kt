package com.smartexpense.manager.domain.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smartexpense.manager.domain.model.AutoLockTimeout
import com.smartexpense.manager.domain.model.BiometricCapability
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.domain.model.SecuritySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore-backed EncryptedSharedPreferences & official BiometricPrompt manager.
 *
 * Security guarantees:
 * - SEM-PIN is salted and PBKDF2-SHA256 hashed locally inside Keystore-backed storage.
 * - Raw PIN, biometric templates, or device credentials are NEVER stored, logged, or synced to Firestore.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SecuritySettings> = _settings.asStateFlow()

    private val _isUnlocked = MutableStateFlow(
        !PinSecurityEngine.isLockActiveOnColdStart(_settings.value.appLockEnabled, _settings.value.method)
    )
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private var lastBackgroundedMillis: Long? = null
    private var suppressNextBackgroundLock: Boolean = false

    fun getBiometricCapability(): BiometricCapability {
        val manager = BiometricManager.from(context)
        val strongRes = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val deviceCredRes = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        val strongAvailable = strongRes == BiometricManager.BIOMETRIC_SUCCESS
        val deviceAvailable = deviceCredRes == BiometricManager.BIOMETRIC_SUCCESS
        val desc = when {
            strongAvailable -> "Fingerprint / strong biometrics and device screen lock ready."
            deviceAvailable -> "Device screen lock (PIN / Pattern / Password) is configured."
            deviceCredRes == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "No screen lock or biometrics enrolled in Android Settings."
            else -> "Biometric hardware unavailable on this device."
        }
        return BiometricCapability(
            strongBiometricAvailable = strongAvailable,
            deviceCredentialAvailable = deviceAvailable,
            statusDescription = desc
        )
    }

    fun configureFirstTimeSecurity(
        method: SecurityMethod,
        pin: String = "",
        confirmPin: String = "",
        enableBiometricQuickUnlock: Boolean = false
    ): Result<Unit> {
        return when (method) {
            SecurityMethod.SEM_PIN -> {
                val setupCheck = PinSecurityEngine.validatePinSetup(pin, confirmPin)
                if (setupCheck.isFailure) return setupCheck
                val salt = PinSecurityEngine.generateSaltBase64()
                val hash = PinSecurityEngine.hashPin(pin, salt)
                prefs.edit()
                    .putBoolean(KEY_CONFIGURED, true)
                    .putBoolean(KEY_LOCK_ENABLED, true)
                    .putString(KEY_METHOD, SecurityMethod.SEM_PIN.name)
                    .putString(KEY_PIN_SALT, salt)
                    .putString(KEY_PIN_HASH, hash)
                    .putBoolean(KEY_BIO_QUICK, enableBiometricQuickUnlock && getBiometricCapability().strongBiometricAvailable)
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .apply()
                _settings.value = loadSettings()
                _isUnlocked.value = true
                Result.success(Unit)
            }
            SecurityMethod.DEVICE_LOCK -> {
                val cap = getBiometricCapability()
                if (!cap.anyDeviceSecurityAvailable) {
                    return Result.failure(IllegalStateException(cap.statusDescription))
                }
                prefs.edit()
                    .putBoolean(KEY_CONFIGURED, true)
                    .putBoolean(KEY_LOCK_ENABLED, true)
                    .putString(KEY_METHOD, SecurityMethod.DEVICE_LOCK.name)
                    .putBoolean(KEY_BIO_QUICK, cap.strongBiometricAvailable)
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .apply()
                _settings.value = loadSettings()
                _isUnlocked.value = true
                Result.success(Unit)
            }
            SecurityMethod.NONE -> {
                prefs.edit()
                    .putBoolean(KEY_CONFIGURED, true)
                    .putBoolean(KEY_LOCK_ENABLED, false)
                    .putString(KEY_METHOD, SecurityMethod.NONE.name)
                    .putBoolean(KEY_BIO_QUICK, false)
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .apply()
                _settings.value = loadSettings()
                _isUnlocked.value = true
                Result.success(Unit)
            }
        }
    }

    fun verifySemPin(pinAttempt: String, nowMillis: Long = System.currentTimeMillis()): Result<Unit> {
        val current = _settings.value
        val remainingSec = PinSecurityEngine.remainingLockoutSeconds(current.lockoutUntilMillis, nowMillis)
        if (remainingSec > 0) {
            return Result.failure(IllegalStateException("Too many incorrect PIN attempts. Try again in ${remainingSec}s."))
        }

        val salt = prefs.getString(KEY_PIN_SALT, "").orEmpty()
        val hash = prefs.getString(KEY_PIN_HASH, "").orEmpty()
        if (PinSecurityEngine.verifyPinConstantTime(pinAttempt, salt, hash)) {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .apply()
            _settings.value = loadSettings()
            _isUnlocked.value = true
            return Result.success(Unit)
        }

        val nextAttempts = current.failedAttempts + 1
        val delayMs = PinSecurityEngine.computeRetryDelayMillis(nextAttempts)
        val nextLockoutUntil = if (delayMs > 0L) nowMillis + delayMs else 0L
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, nextAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, nextLockoutUntil)
            .apply()
        _settings.value = loadSettings()

        val msg = if (delayMs > 0L) {
            "Incorrect SEM-PIN. Retry locked for ${delayMs / 1000L} seconds."
        } else {
            "Incorrect SEM-PIN. Please try again."
        }
        return Result.failure(IllegalArgumentException(msg))
    }

    fun changeSemPin(
        currentPin: String,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> {
        val salt = prefs.getString(KEY_PIN_SALT, "").orEmpty()
        val hash = prefs.getString(KEY_PIN_HASH, "").orEmpty()
        if (!PinSecurityEngine.verifyPinConstantTime(currentPin, salt, hash)) {
            return Result.failure(IllegalArgumentException("Current SEM-PIN is incorrect."))
        }
        val checkNew = PinSecurityEngine.validatePinSetup(newPin, confirmNewPin)
        if (checkNew.isFailure) return checkNew
        val nextSalt = PinSecurityEngine.generateSaltBase64()
        val nextHash = PinSecurityEngine.hashPin(newPin, nextSalt)
        prefs.edit()
            .putString(KEY_PIN_SALT, nextSalt)
            .putString(KEY_PIN_HASH, nextHash)
            .putBoolean(KEY_LOCK_ENABLED, true)
            .putString(KEY_METHOD, SecurityMethod.SEM_PIN.name)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        _settings.value = loadSettings()
        return Result.success(Unit)
    }

    /**
     * Requires prior verified identity proof (fresh Firebase Google re-auth ID token or OS BiometricPrompt)
     * before replacing a forgotten SEM-PIN. Never silently unlocks to dashboard.
     */
    fun resetForgottenPinAfterIdentityVerification(
        identityVerified: Boolean,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> {
        if (!identityVerified) {
            return Result.failure(SecurityException("Account or device verification required before resetting SEM-PIN."))
        }
        val checkNew = PinSecurityEngine.validatePinSetup(newPin, confirmNewPin)
        if (checkNew.isFailure) return checkNew
        val nextSalt = PinSecurityEngine.generateSaltBase64()
        val nextHash = PinSecurityEngine.hashPin(newPin, nextSalt)
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putBoolean(KEY_LOCK_ENABLED, true)
            .putString(KEY_METHOD, SecurityMethod.SEM_PIN.name)
            .putString(KEY_PIN_SALT, nextSalt)
            .putString(KEY_PIN_HASH, nextHash)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        _settings.value = loadSettings()
        _isUnlocked.value = true
        return Result.success(Unit)
    }

    fun updateSecurityMethod(method: SecurityMethod): Result<Unit> {
        return when (method) {
            SecurityMethod.SEM_PIN -> {
                if (!_settings.value.hasSemPinSet) {
                    Result.failure(IllegalStateException("Create a 4–6 digit SEM-PIN first."))
                } else {
                    prefs.edit()
                        .putBoolean(KEY_LOCK_ENABLED, true)
                        .putString(KEY_METHOD, SecurityMethod.SEM_PIN.name)
                        .apply()
                    _settings.value = loadSettings()
                    Result.success(Unit)
                }
            }
            SecurityMethod.DEVICE_LOCK -> {
                val cap = getBiometricCapability()
                if (!cap.anyDeviceSecurityAvailable) {
                    Result.failure(IllegalStateException(cap.statusDescription))
                } else {
                    prefs.edit()
                        .putBoolean(KEY_LOCK_ENABLED, true)
                        .putString(KEY_METHOD, SecurityMethod.DEVICE_LOCK.name)
                        .putBoolean(KEY_BIO_QUICK, cap.strongBiometricAvailable)
                        .apply()
                    _settings.value = loadSettings()
                    Result.success(Unit)
                }
            }
            SecurityMethod.NONE -> {
                prefs.edit()
                    .putBoolean(KEY_LOCK_ENABLED, false)
                    .putString(KEY_METHOD, SecurityMethod.NONE.name)
                    .putBoolean(KEY_BIO_QUICK, false)
                    .apply()
                _settings.value = loadSettings()
                _isUnlocked.value = true
                Result.success(Unit)
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean): Result<Unit> {
        if (!enabled) {
            return updateSecurityMethod(SecurityMethod.NONE)
        }
        val current = _settings.value
        val targetMethod = when {
            current.hasSemPinSet -> SecurityMethod.SEM_PIN
            getBiometricCapability().anyDeviceSecurityAvailable -> SecurityMethod.DEVICE_LOCK
            else -> return Result.failure(IllegalStateException("Set up SEM-PIN or Phone Screen Lock to enable App Lock."))
        }
        return updateSecurityMethod(targetMethod)
    }

    fun setFingerprintQuickUnlock(enabled: Boolean): Result<Unit> {
        val cap = getBiometricCapability()
        if (enabled && !cap.strongBiometricAvailable) {
            return Result.failure(IllegalStateException("Fingerprint/strong biometrics unavailable or not enrolled on this device."))
        }
        prefs.edit().putBoolean(KEY_BIO_QUICK, enabled).apply()
        _settings.value = loadSettings()
        return Result.success(Unit)
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        prefs.edit().putInt(KEY_AUTO_LOCK_SECONDS, timeout.seconds).apply()
        _settings.value = loadSettings()
    }

    fun setLockOnAppClose(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ON_CLOSE, enabled).apply()
        _settings.value = loadSettings()
    }

    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        if (suppressNextBackgroundLock) return
        lastBackgroundedMillis = nowMillis
    }

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        if (suppressNextBackgroundLock) {
            suppressNextBackgroundLock = false
            return
        }
        val current = _settings.value
        val bg = lastBackgroundedMillis ?: return
        if (PinSecurityEngine.shouldLockOnForeground(
                method = current.method,
                appLockEnabled = current.appLockEnabled,
                lockOnAppClose = current.lockOnAppClose,
                timeout = current.autoLockTimeout,
                lastBackgroundedMillis = bg,
                nowMillis = nowMillis
            )
        ) {
            _isUnlocked.value = false
        }
    }

    fun lockNow() {
        val current = _settings.value
        if (current.appLockEnabled && current.method != SecurityMethod.NONE) {
            _isUnlocked.value = false
        }
    }

    fun launchSystemBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Unlock Smart Expense Manager",
        subtitle: String = "Verify using your phone screen lock or fingerprint",
        allowDeviceCredential: Boolean = true,
        onSuccess: () -> Unit = { _isUnlocked.value = true },
        onError: (String) -> Unit
    ) {
        val cap = getBiometricCapability()
        if (!cap.anyDeviceSecurityAvailable && allowDeviceCredential) {
            onError(cap.statusDescription)
            return
        }
        if (!cap.strongBiometricAvailable && !allowDeviceCredential) {
            onError("Fingerprint authentication is not enrolled on this device.")
            return
        }

        suppressNextBackgroundLock = true
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    suppressNextBackgroundLock = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    suppressNextBackgroundLock = false
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // System UI already displays retry prompt
                }
            }
        )

        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (allowDeviceCredential) {
            infoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            infoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use SEM-PIN")
        }

        prompt.authenticate(infoBuilder.build())
    }

    fun clearSecurityOnSignOut() {
        _isUnlocked.value = true
    }

    private fun loadSettings(): SecuritySettings {
        val method = runCatching {
            SecurityMethod.valueOf(prefs.getString(KEY_METHOD, SecurityMethod.NONE.name).orEmpty())
        }.getOrDefault(SecurityMethod.NONE)
        val timeoutSec = prefs.getInt(KEY_AUTO_LOCK_SECONDS, AutoLockTimeout.IMMEDIATE.seconds)
        val timeout = AutoLockTimeout.entries.firstOrNull { it.seconds == timeoutSec }
            ?: AutoLockTimeout.IMMEDIATE
        val hasPin = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
        return SecuritySettings(
            securityConfigured = prefs.getBoolean(KEY_CONFIGURED, false),
            appLockEnabled = prefs.getBoolean(KEY_LOCK_ENABLED, false) && method != SecurityMethod.NONE,
            method = method,
            fingerprintQuickUnlockEnabled = prefs.getBoolean(KEY_BIO_QUICK, false),
            autoLockTimeout = timeout,
            lockOnAppClose = prefs.getBoolean(KEY_LOCK_ON_CLOSE, true),
            hasSemPinSet = hasPin,
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0),
            lockoutUntilMillis = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        )
    }

    companion object {
        private const val PREFS_NAME = "sem_secure_app_lock_prefs"
        private const val KEY_CONFIGURED = "security_configured"
        private const val KEY_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_METHOD = "security_method"
        private const val KEY_PIN_SALT = "sem_pin_salt"
        private const val KEY_PIN_HASH = "sem_pin_hash"
        private const val KEY_BIO_QUICK = "bio_quick_unlock"
        private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_seconds"
        private const val KEY_LOCK_ON_CLOSE = "lock_on_close"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }
}
