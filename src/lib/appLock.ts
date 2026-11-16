import {
  AutoLockTimeoutSeconds,
  DeviceSecurityCapability,
  SecurityMethod,
  SecuritySettings,
} from '../types/expense';

const PBKDF2_ITERATIONS = 120_000;
const KEY_BITS = 256;
const SALT_BYTES = 16;
const PIN_REGEX = /^\d{4,6}$/;

export const DEFAULT_SECURITY_SETTINGS: SecuritySettings = {
  securityConfigured: false,
  appLockEnabled: false,
  method: 'NONE',
  fingerprintQuickUnlockEnabled: false,
  autoLockTimeoutSeconds: 0,
  lockOnAppClose: true,
  hasSemPinSet: false,
  failedAttempts: 0,
  lockoutUntilMillis: 0,
};

function bytesToBase64(bytes: Uint8Array): string {
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(bytes).toString('base64');
  }
  let bin = '';
  for (let i = 0; i < bytes.length; i += 1) {
    bin += String.fromCharCode(bytes[i]);
  }
  return btoa(bin);
}

function base64ToBytes(b64: string): Uint8Array {
  if (typeof Buffer !== 'undefined') {
    return new Uint8Array(Buffer.from(b64, 'base64'));
  }
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

export function validatePinFormat(pin: string): string | null {
  if (!PIN_REGEX.test(pin)) {
    return 'SEM-PIN must be 4 to 6 numeric digits.';
  }
  return null;
}

export function validatePinSetup(pin: string, confirmPin: string): string | null {
  const formatErr = validatePinFormat(pin);
  if (formatErr) return formatErr;
  if (pin !== confirmPin) {
    return 'PIN entries do not match. Please enter the exact same PIN twice.';
  }
  return null;
}

export function generateSaltBase64(): string {
  const salt = new Uint8Array(SALT_BYTES);
  globalThis.crypto.getRandomValues(salt);
  return bytesToBase64(salt);
}

export async function hashSemPin(pin: string, saltBase64: string): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) {
    throw new Error('Web Crypto SubtleCrypto API unavailable.');
  }
  const encoder = new TextEncoder();
  const keyMaterial = await subtle.importKey(
    'raw',
    encoder.encode(pin),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const saltBytes = base64ToBytes(saltBase64);
  const derived = await subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: saltBytes as BufferSource,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    keyMaterial,
    KEY_BITS
  );
  return bytesToBase64(new Uint8Array(derived));
}

export async function verifyPinConstantTime(
  pin: string,
  saltBase64: string | undefined,
  expectedHashBase64: string | undefined
): Promise<boolean> {
  if (!saltBase64 || !expectedHashBase64 || !PIN_REGEX.test(pin)) return false;
  const candidateHash = await hashSemPin(pin, saltBase64);
  const a = base64ToBytes(candidateHash);
  const b = base64ToBytes(expectedHashBase64);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a[i] ^ b[i];
  }
  return diff === 0;
}

export function computeRetryDelayMillis(failedAttempts: number): number {
  if (failedAttempts < 3) return 0;
  if (failedAttempts === 3) return 15_000;
  if (failedAttempts === 4) return 30_000;
  return 60_000;
}

export function remainingLockoutSeconds(
  lockoutUntilMillis: number,
  nowMillis: number = Date.now()
): number {
  const diff = lockoutUntilMillis - nowMillis;
  if (diff <= 0) return 0;
  return Math.ceil(diff / 1000);
}

export function isLockActiveOnColdStart(settings: SecuritySettings): boolean {
  return settings.securityConfigured && settings.appLockEnabled && settings.method !== 'NONE';
}

export function shouldLockOnResume(
  settings: SecuritySettings,
  lastBackgroundedMillis: number | null,
  nowMillis: number = Date.now()
): boolean {
  if (!settings.appLockEnabled || settings.method === 'NONE') return false;
  if (lastBackgroundedMillis == null) return true;
  if (settings.autoLockTimeoutSeconds === 0) return true;
  const elapsed = Math.max(0, nowMillis - lastBackgroundedMillis);
  return elapsed >= settings.autoLockTimeoutSeconds * 1000;
}

export function canDispatchDeepLink(
  settings: SecuritySettings,
  isUnlocked: boolean
): boolean {
  if (!settings.securityConfigured) return false;
  if (!settings.appLockEnabled || settings.method === 'NONE') return true;
  return isUnlocked;
}

const securityStorageKey = (uid: string) => `sem_v1_${uid}_security_lock`;

export function loadSecuritySettings(uid: string): SecuritySettings {
  try {
    const raw = localStorage.getItem(securityStorageKey(uid));
    if (!raw) return { ...DEFAULT_SECURITY_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<SecuritySettings>;
    const method: SecurityMethod =
      parsed.method === 'SEM_PIN' || parsed.method === 'DEVICE_LOCK' || parsed.method === 'NONE'
        ? parsed.method
        : 'NONE';
    const timeout: AutoLockTimeoutSeconds =
      parsed.autoLockTimeoutSeconds === 0 ||
      parsed.autoLockTimeoutSeconds === 30 ||
      parsed.autoLockTimeoutSeconds === 60 ||
      parsed.autoLockTimeoutSeconds === 300
        ? parsed.autoLockTimeoutSeconds
        : 0;
    return {
      securityConfigured: Boolean(parsed.securityConfigured),
      appLockEnabled: Boolean(parsed.appLockEnabled) && method !== 'NONE',
      method,
      fingerprintQuickUnlockEnabled: Boolean(parsed.fingerprintQuickUnlockEnabled),
      autoLockTimeoutSeconds: timeout,
      lockOnAppClose: parsed.lockOnAppClose ?? true,
      hasSemPinSet: Boolean(parsed.pinHashBase64 && parsed.pinSaltBase64),
      pinSaltBase64: parsed.pinSaltBase64,
      pinHashBase64: parsed.pinHashBase64,
      webAuthnCredentialIdBase64: parsed.webAuthnCredentialIdBase64,
      failedAttempts: Number(parsed.failedAttempts || 0),
      lockoutUntilMillis: Number(parsed.lockoutUntilMillis || 0),
    };
  } catch {
    return { ...DEFAULT_SECURITY_SETTINGS };
  }
}

export function saveSecuritySettings(uid: string, settings: SecuritySettings): void {
  // SEM-PIN hash and salt reside ONLY in local device storage — never uploaded to Firestore or logged.
  localStorage.setItem(securityStorageKey(uid), JSON.stringify(settings));
}

export async function createOrReplaceSemPinSettings(
  current: SecuritySettings,
  newPin: string,
  confirmPin: string,
  enableFingerprintQuickUnlock: boolean
): Promise<{ settings?: SecuritySettings; error?: string }> {
  const err = validatePinSetup(newPin, confirmPin);
  if (err) return { error: err };
  const salt = generateSaltBase64();
  const hash = await hashSemPin(newPin, salt);
  return {
    settings: {
      ...current,
      securityConfigured: true,
      appLockEnabled: true,
      method: 'SEM_PIN',
      hasSemPinSet: true,
      pinSaltBase64: salt,
      pinHashBase64: hash,
      fingerprintQuickUnlockEnabled: enableFingerprintQuickUnlock,
      failedAttempts: 0,
      lockoutUntilMillis: 0,
    },
  };
}

export async function verifyAndAdvanceLockout(
  current: SecuritySettings,
  pinAttempt: string,
  nowMillis: number = Date.now()
): Promise<{ ok: boolean; updated: SecuritySettings; error?: string }> {
  const remaining = remainingLockoutSeconds(current.lockoutUntilMillis, nowMillis);
  if (remaining > 0) {
    return {
      ok: false,
      updated: current,
      error: `Too many incorrect attempts. Try again in ${remaining}s.`,
    };
  }
  const matched = await verifyPinConstantTime(
    pinAttempt,
    current.pinSaltBase64,
    current.pinHashBase64
  );
  if (matched) {
    return {
      ok: true,
      updated: {
        ...current,
        failedAttempts: 0,
        lockoutUntilMillis: 0,
      },
    };
  }
  const nextAttempts = current.failedAttempts + 1;
  const delayMs = computeRetryDelayMillis(nextAttempts);
  const lockoutUntilMillis = delayMs > 0 ? nowMillis + delayMs : 0;
  return {
    ok: false,
    updated: {
      ...current,
      failedAttempts: nextAttempts,
      lockoutUntilMillis,
    },
    error:
      delayMs > 0
        ? `Incorrect SEM-PIN. Retry locked for ${Math.round(delayMs / 1000)} seconds.`
        : 'Incorrect SEM-PIN. Please try again.',
  };
}

export async function changeExistingSemPin(
  current: SecuritySettings,
  currentPin: string,
  newPin: string,
  confirmNewPin: string
): Promise<{ settings?: SecuritySettings; error?: string }> {
  const validCurrent = await verifyPinConstantTime(
    currentPin,
    current.pinSaltBase64,
    current.pinHashBase64
  );
  if (!validCurrent) {
    return { error: 'Current SEM-PIN is incorrect.' };
  }
  return createOrReplaceSemPinSettings(
    current,
    newPin,
    confirmNewPin,
    current.fingerprintQuickUnlockEnabled
  );
}

export async function resetForgottenPinAfterVerifiedIdentity(
  current: SecuritySettings,
  identityVerified: boolean,
  newPin: string,
  confirmNewPin: string
): Promise<{ settings?: SecuritySettings; error?: string }> {
  if (!identityVerified) {
    return { error: 'Verified account or device authentication is required before resetting SEM-PIN.' };
  }
  return createOrReplaceSemPinSettings(
    current,
    newPin,
    confirmNewPin,
    current.fingerprintQuickUnlockEnabled
  );
}

export async function checkDeviceSecurityCapability(): Promise<DeviceSecurityCapability> {
  if (
    typeof window === 'undefined' ||
    !window.PublicKeyCredential ||
    typeof window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable !== 'function'
  ) {
    return {
      platformAuthenticatorAvailable: false,
      reason: 'Browser/OS platform biometric authenticator is not available here. Use SEM-PIN or native Android APK for Fingerprint/Device Lock.',
    };
  }
  try {
    const ok = await window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    return ok
      ? {
          platformAuthenticatorAvailable: true,
          reason: 'Platform biometric / device screen lock verification supported.',
        }
      : {
          platformAuthenticatorAvailable: false,
          reason: 'No device screen lock or platform authenticator enrolled in this browser.',
        };
  } catch {
    return {
      platformAuthenticatorAvailable: false,
      reason: 'Unable to query device biometric authenticator.',
    };
  }
}

export async function registerPlatformBiometricCredential(
  uid: string,
  displayName: string
): Promise<{ credentialIdBase64?: string; error?: string }> {
  try {
    const challenge = new Uint8Array(32);
    globalThis.crypto.getRandomValues(challenge);
    const cred = (await navigator.credentials.create({
      publicKey: {
        challenge,
        rp: { name: 'Smart Expense Manager' },
        user: {
          id: new TextEncoder().encode(uid),
          name: displayName || 'user',
          displayName: displayName || 'Smart Expense Manager User',
        },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
        authenticatorSelection: {
          authenticatorAttachment: 'platform',
          userVerification: 'required',
        },
        timeout: 60_000,
      },
    })) as PublicKeyCredential | null;
    if (!cred) {
      return { error: 'Biometric enrollment was cancelled.' };
    }
    return { credentialIdBase64: bytesToBase64(new Uint8Array(cred.rawId)) };
  } catch (err) {
    return {
      error:
        err instanceof Error
          ? err.message
          : 'Device lock authentication could not be activated on this browser.',
    };
  }
}

export async function verifyPlatformBiometricCredential(
  credentialIdBase64: string
): Promise<{ ok: boolean; error?: string }> {
  try {
    const challenge = new Uint8Array(32);
    globalThis.crypto.getRandomValues(challenge);
    const assertion = await navigator.credentials.get({
      publicKey: {
        challenge,
        allowCredentials: [
          {
            type: 'public-key',
            id: base64ToBytes(credentialIdBase64) as BufferSource,
          },
        ],
        userVerification: 'required',
        timeout: 60_000,
      },
    });
    return assertion ? { ok: true } : { ok: false, error: 'Verification cancelled.' };
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : 'Device biometric verification failed.',
    };
  }
}
