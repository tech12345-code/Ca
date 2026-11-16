package com.smartexpense.manager.domain.security

import com.smartexpense.manager.domain.model.AutoLockTimeout
import com.smartexpense.manager.domain.model.SecurityMethod
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure Kotlin cryptographic and policy engine for SEM-PIN & App Lock rules.
 * Never logs or persists raw PIN strings.
 */
object PinSecurityEngine {

    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private val pinRegex = Regex("^\\d{4,6}$")

    fun validatePinFormat(pin: String): Result<Unit> {
        return if (pinRegex.matches(pin)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("SEM-PIN must be 4 to 6 numeric digits."))
        }
    }

    fun validatePinSetup(pin: String, confirmPin: String): Result<Unit> {
        val formatCheck = validatePinFormat(pin)
        if (formatCheck.isFailure) return formatCheck
        if (pin != confirmPin) {
            return Result.failure(IllegalArgumentException("PIN entries do not match. Please re-enter."))
        }
        return Result.success(Unit)
    }

    fun generateSaltBase64(random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun hashPin(pin: String, saltBase64: String): String {
        val salt = Base64.getDecoder().decode(saltBase64)
        val chars = pin.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val derived = factory.generateSecret(spec).encoded
            spec.clearPassword()
            Base64.getEncoder().encodeToString(derived)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verifyPinConstantTime(pin: String, saltBase64: String, expectedHashBase64: String): Boolean {
        if (saltBase64.isBlank() || expectedHashBase64.isBlank()) return false
        if (!pinRegex.matches(pin)) return false
        val actual = Base64.getDecoder().decode(hashPin(pin, saltBase64))
        val expected = Base64.getDecoder().decode(expectedHashBase64)
        return MessageDigest.isEqual(actual, expected)
    }

    fun computeRetryDelayMillis(failedAttempts: Int): Long {
        return when {
            failedAttempts < 3 -> 0L
            failedAttempts == 3 -> 15_000L
            failedAttempts == 4 -> 30_000L
            else -> 60_000L
        }
    }

    fun remainingLockoutSeconds(lockoutUntilMillis: Long, nowMillis: Long): Int {
        val diff = lockoutUntilMillis - nowMillis
        if (diff <= 0L) return 0
        return ((diff + 999L) / 1000L).toInt()
    }

    fun shouldLockOnForeground(
        method: SecurityMethod,
        appLockEnabled: Boolean,
        lockOnAppClose: Boolean,
        timeout: AutoLockTimeout,
        lastBackgroundedMillis: Long?,
        nowMillis: Long
    ): Boolean {
        if (!appLockEnabled || method == SecurityMethod.NONE) return false
        if (lastBackgroundedMillis == null) return true
        if (timeout == AutoLockTimeout.IMMEDIATE) return true
        val elapsedMillis = (nowMillis - lastBackgroundedMillis).coerceAtLeast(0L)
        return elapsedMillis >= timeout.seconds * 1000L
    }

    fun isLockActiveOnColdStart(appLockEnabled: Boolean, method: SecurityMethod): Boolean {
        return appLockEnabled && method != SecurityMethod.NONE
    }

    fun shouldEnableFlagSecure(appLockEnabled: Boolean, method: SecurityMethod): Boolean {
        return appLockEnabled && method != SecurityMethod.NONE
    }

    fun canDispatchDeepLink(
        securityConfigured: Boolean,
        appLockEnabled: Boolean,
        method: SecurityMethod,
        isUnlocked: Boolean
    ): Boolean {
        if (!securityConfigured) return false
        if (!appLockEnabled || method == SecurityMethod.NONE) return true
        return isUnlocked
    }
}
