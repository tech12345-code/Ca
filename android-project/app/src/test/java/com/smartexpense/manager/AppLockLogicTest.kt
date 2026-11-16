package com.smartexpense.manager

import com.smartexpense.manager.domain.model.AutoLockTimeout
import com.smartexpense.manager.domain.model.SecurityMethod
import com.smartexpense.manager.domain.security.PinSecurityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockLogicTest {

    @Test
    fun pinFormat_accepts4To6DigitsAndRejectsInvalid() {
        assertTrue(PinSecurityEngine.validatePinFormat("1234").isSuccess)
        assertTrue(PinSecurityEngine.validatePinFormat("12345").isSuccess)
        assertTrue(PinSecurityEngine.validatePinFormat("098765").isSuccess)

        assertTrue(PinSecurityEngine.validatePinFormat("123").isFailure)
        assertTrue(PinSecurityEngine.validatePinFormat("1234567").isFailure)
        assertTrue(PinSecurityEngine.validatePinFormat("12a4").isFailure)
        assertTrue(PinSecurityEngine.validatePinFormat("").isFailure)
    }

    @Test
    fun pinSetup_requiresMatchingDoubleEntry() {
        assertTrue(PinSecurityEngine.validatePinSetup("4829", "4829").isSuccess)
        assertTrue(PinSecurityEngine.validatePinSetup("4829", "4820").isFailure)
    }

    @Test
    fun pbkdf2Hashing_usesSaltAndVerifiesConstantTime() {
        val salt1 = PinSecurityEngine.generateSaltBase64()
        val salt2 = PinSecurityEngine.generateSaltBase64()
        val hash1 = PinSecurityEngine.hashPin("7391", salt1)
        val hash2 = PinSecurityEngine.hashPin("7391", salt2)

        assertNotEquals(hash1, hash2)
        assertNotEquals("7391", hash1)
        assertTrue(PinSecurityEngine.verifyPinConstantTime("7391", salt1, hash1))
        assertFalse(PinSecurityEngine.verifyPinConstantTime("7392", salt1, hash1))
    }

    @Test
    fun retryDelay_escalatesAfterRepeatedFailedAttempts() {
        assertEquals(0L, PinSecurityEngine.computeRetryDelayMillis(1))
        assertEquals(0L, PinSecurityEngine.computeRetryDelayMillis(2))
        assertEquals(15_000L, PinSecurityEngine.computeRetryDelayMillis(3))
        assertEquals(30_000L, PinSecurityEngine.computeRetryDelayMillis(4))
        assertEquals(60_000L, PinSecurityEngine.computeRetryDelayMillis(5))
    }

    @Test
    fun autoLockForeground_respectsConfiguredTimeout() {
        val t0 = 1_000_000L
        assertTrue(
            PinSecurityEngine.shouldLockOnForeground(
                method = SecurityMethod.SEM_PIN,
                appLockEnabled = true,
                lockOnAppClose = true,
                timeout = AutoLockTimeout.IMMEDIATE,
                lastBackgroundedMillis = t0,
                nowMillis = t0 + 500L
            )
        )
        assertFalse(
            PinSecurityEngine.shouldLockOnForeground(
                method = SecurityMethod.SEM_PIN,
                appLockEnabled = true,
                lockOnAppClose = true,
                timeout = AutoLockTimeout.SECONDS_30,
                lastBackgroundedMillis = t0,
                nowMillis = t0 + 10_000L
            )
        )
        assertTrue(
            PinSecurityEngine.shouldLockOnForeground(
                method = SecurityMethod.SEM_PIN,
                appLockEnabled = true,
                lockOnAppClose = true,
                timeout = AutoLockTimeout.SECONDS_30,
                lastBackgroundedMillis = t0,
                nowMillis = t0 + 30_000L
            )
        )
        assertFalse(
            PinSecurityEngine.shouldLockOnForeground(
                method = SecurityMethod.NONE,
                appLockEnabled = false,
                lockOnAppClose = true,
                timeout = AutoLockTimeout.IMMEDIATE,
                lastBackgroundedMillis = t0,
                nowMillis = t0 + 500_000L
            )
        )
    }

    @Test
    fun flagSecureAndDeepLinkGating_protectFinancialState() {
        assertTrue(PinSecurityEngine.shouldEnableFlagSecure(true, SecurityMethod.SEM_PIN))
        assertTrue(PinSecurityEngine.shouldEnableFlagSecure(true, SecurityMethod.DEVICE_LOCK))
        assertFalse(PinSecurityEngine.shouldEnableFlagSecure(false, SecurityMethod.NONE))

        assertFalse(
            PinSecurityEngine.canDispatchDeepLink(
                securityConfigured = true,
                appLockEnabled = true,
                method = SecurityMethod.SEM_PIN,
                isUnlocked = false
            )
        )
        assertTrue(
            PinSecurityEngine.canDispatchDeepLink(
                securityConfigured = true,
                appLockEnabled = true,
                method = SecurityMethod.SEM_PIN,
                isUnlocked = true
            )
        )
    }
}
