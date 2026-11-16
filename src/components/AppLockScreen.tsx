import React, { useEffect, useState } from 'react';
import { Delete, Fingerprint, Lock, Shield, AlertTriangle, X } from 'lucide-react';
import { SecuritySettings } from '../types/expense';
import { remainingLockoutSeconds, validatePinSetup } from '../lib/appLock';

interface AppLockScreenProps {
  userEmail: string;
  isFirebaseSignedIn: boolean;
  settings: SecuritySettings;
  onVerifyPin: (pinAttempt: string) => Promise<{ ok: boolean; error?: string }>;
  onTriggerDeviceUnlock: () => Promise<{ ok: boolean; error?: string }>;
  onRecoverWithGoogleReauth: (
    newPin: string,
    confirmPin: string
  ) => Promise<{ ok: boolean; error?: string }>;
  onRecoverWithDeviceAuth: (
    newPin: string,
    confirmPin: string
  ) => Promise<{ ok: boolean; error?: string }>;
}

export const AppLockScreen: React.FC<AppLockScreenProps> = ({
  userEmail,
  isFirebaseSignedIn,
  settings,
  onVerifyPin,
  onTriggerDeviceUnlock,
  onRecoverWithGoogleReauth,
  onRecoverWithDeviceAuth,
}) => {
  const [enteredPin, setEnteredPin] = useState('');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [lockoutSec, setLockoutSec] = useState(() =>
    remainingLockoutSeconds(settings.lockoutUntilMillis)
  );
  const [showForgotModal, setShowForgotModal] = useState(false);
  const [recoveryPin, setRecoveryPin] = useState('');
  const [recoveryConfirmPin, setRecoveryConfirmPin] = useState('');
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [recoveryBusy, setRecoveryBusy] = useState(false);

  useEffect(() => {
    const syncCountdown = () => {
      setLockoutSec(remainingLockoutSeconds(settings.lockoutUntilMillis));
    };
    syncCountdown();
    if (settings.lockoutUntilMillis <= Date.now()) return;
    const timer = window.setInterval(syncCountdown, 1000);
    return () => window.clearInterval(timer);
  }, [settings.lockoutUntilMillis]);

  const handleDigit = (digit: string) => {
    if (lockoutSec > 0 || busy) return;
    if (enteredPin.length >= 6) return;
    setErrorMsg(null);
    setEnteredPin((prev) => prev + digit);
  };

  const handleBackspace = () => {
    if (lockoutSec > 0 || busy) return;
    setEnteredPin((prev) => prev.slice(0, -1));
  };

  const handleSubmitPin = async () => {
    if (lockoutSec > 0 || busy) return;
    if (enteredPin.length < 4) {
      setErrorMsg('Enter at least 4 digits.');
      return;
    }
    setBusy(true);
    const res = await onVerifyPin(enteredPin);
    setBusy(false);
    if (!res.ok) {
      setErrorMsg(res.error || 'Incorrect SEM-PIN.');
      setEnteredPin('');
    }
  };

  const handleBiometricUnlock = async () => {
    if (busy) return;
    setBusy(true);
    setErrorMsg(null);
    const res = await onTriggerDeviceUnlock();
    setBusy(false);
    if (!res.ok) {
      setErrorMsg(res.error || 'Device authentication failed.');
    }
  };

  const handleRecoveryGoogle = async () => {
    const formatErr = validatePinSetup(recoveryPin, recoveryConfirmPin);
    if (formatErr) {
      setRecoveryError(formatErr);
      return;
    }
    setRecoveryBusy(true);
    setRecoveryError(null);
    const res = await onRecoverWithGoogleReauth(recoveryPin, recoveryConfirmPin);
    setRecoveryBusy(false);
    if (res.ok) {
      setShowForgotModal(false);
      setRecoveryPin('');
      setRecoveryConfirmPin('');
    } else {
      setRecoveryError(res.error || 'Google verification failed.');
    }
  };

  const handleRecoveryDevice = async () => {
    const formatErr = validatePinSetup(recoveryPin, recoveryConfirmPin);
    if (formatErr) {
      setRecoveryError(formatErr);
      return;
    }
    setRecoveryBusy(true);
    setRecoveryError(null);
    const res = await onRecoverWithDeviceAuth(recoveryPin, recoveryConfirmPin);
    setRecoveryBusy(false);
    if (res.ok) {
      setShowForgotModal(false);
      setRecoveryPin('');
      setRecoveryConfirmPin('');
    } else {
      setRecoveryError(res.error || 'Device verification failed.');
    }
  };

  const isDeviceLockMode = settings.method === 'DEVICE_LOCK';
  const showBioShortcut =
    Boolean(settings.webAuthnCredentialIdBase64) &&
    (settings.method === 'DEVICE_LOCK' || settings.fingerprintQuickUnlockEnabled);

  return (
    <div className="min-h-screen bg-[#0B0D0F] text-[#F3F5F7] flex items-center justify-center p-4">
      <div className="w-full max-w-md bg-[#121519] border border-white/10 rounded-3xl p-7 flex flex-col items-center space-y-6">
        <div className="flex flex-col items-center text-center space-y-2">
          <div className="w-16 h-16 rounded-full bg-[#181C22] border border-[#C6FF33]/50 flex items-center justify-center text-[#C6FF33]">
            <Shield className="w-8 h-8" />
          </div>
          <h1 className="text-lg font-bold tracking-tight text-[#F3F5F7]">
            Smart Expense Manager
          </h1>
          {userEmail && <p className="text-xs text-[#9CA3AF]">{userEmail}</p>}
          <h2 className="text-2xl font-semibold text-[#F3F5F7] pt-1">
            {isDeviceLockMode ? 'Unlock with Device Lock' : 'Enter SEM-PIN'}
          </h2>
          <p className="text-xs text-[#9CA3AF] max-w-xs">
            {isDeviceLockMode
              ? 'Verify with your device screen lock or biometric authenticator to access your balances.'
              : 'Enter your 4–6 digit numeric SEM-PIN before viewing financial balances or ledgers.'}
          </p>
        </div>

        {isDeviceLockMode ? (
          <div className="w-full flex flex-col items-center space-y-5 py-4">
            <button
              type="button"
              disabled={busy}
              onClick={handleBiometricUnlock}
              className="w-20 h-20 rounded-full bg-[#181C22] border-2 border-[#C6FF33] flex items-center justify-center text-[#C6FF33] hover:bg-[#1f242c] cursor-pointer"
              aria-label="Verify device screen lock"
            >
              <Fingerprint className="w-10 h-10" />
            </button>
            {errorMsg && (
              <p className="text-xs text-[#EF4444] text-center">{errorMsg}</p>
            )}
            <button
              type="button"
              disabled={busy}
              onClick={handleBiometricUnlock}
              className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] inline-flex items-center justify-center gap-2 cursor-pointer"
            >
              <Lock className="w-4 h-4" />
              <span>{busy ? 'Verifying...' : 'Verify Phone Screen Lock'}</span>
            </button>
          </div>
        ) : (
          <div className="w-full flex flex-col items-center space-y-5">
            {/* 4-6 digit indicators */}
            <div className="flex items-center gap-3.5" aria-label="Entered PIN digits">
              {[0, 1, 2, 3, 4, 5].map((idx) => {
                const filled = idx < enteredPin.length;
                const optionalSlot = idx >= 4;
                return (
                  <span
                    key={idx}
                    className={`rounded-full border transition-all ${
                      filled
                        ? 'w-4 h-4 bg-[#C6FF33] border-[#C6FF33]'
                        : optionalSlot
                          ? 'w-3.5 h-3.5 bg-[#0B0D0F] border-white/15'
                          : 'w-3.5 h-3.5 bg-[#0B0D0F] border-white/35'
                    }`}
                  />
                );
              })}
            </div>

            {lockoutSec > 0 ? (
              <p className="text-xs font-medium text-[#EF4444] text-center">
                Too many incorrect attempts. Try again in {lockoutSec}s.
              </p>
            ) : errorMsg ? (
              <p className="text-xs text-[#EF4444] text-center">{errorMsg}</p>
            ) : (
              <p className="text-[11px] text-[#9CA3AF]">4–6 numeric digits</p>
            )}

            {/* Numeric keypad */}
            <div className="grid grid-cols-3 gap-3.5">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((digit) => (
                <button
                  key={digit}
                  type="button"
                  disabled={lockoutSec > 0 || busy}
                  onClick={() => handleDigit(digit)}
                  className="w-16 h-16 rounded-full bg-[#181C22] border border-white/10 text-xl font-mono-num font-semibold text-[#F3F5F7] hover:border-[#C6FF33]/60 active:scale-95 transition-all disabled:opacity-40 cursor-pointer"
                >
                  {digit}
                </button>
              ))}

              {showBioShortcut ? (
                <button
                  type="button"
                  disabled={busy}
                  onClick={handleBiometricUnlock}
                  aria-label="Unlock with Biometrics"
                  className="w-16 h-16 rounded-full bg-[#181C22] border border-white/10 flex items-center justify-center text-[#C6FF33] hover:border-[#C6FF33] cursor-pointer"
                >
                  <Fingerprint className="w-6 h-6" />
                </button>
              ) : (
                <button
                  type="button"
                  disabled={lockoutSec > 0 || busy || enteredPin.length < 4}
                  onClick={handleSubmitPin}
                  className="w-16 h-16 rounded-full bg-[#C6FF33]/15 border border-[#C6FF33]/50 text-xs font-bold text-[#C6FF33] hover:bg-[#C6FF33] hover:text-[#0B0D0F] disabled:opacity-35 transition-all cursor-pointer"
                >
                  OK
                </button>
              )}

              <button
                type="button"
                disabled={lockoutSec > 0 || busy}
                onClick={() => handleDigit('0')}
                className="w-16 h-16 rounded-full bg-[#181C22] border border-white/10 text-xl font-mono-num font-semibold text-[#F3F5F7] hover:border-[#C6FF33]/60 active:scale-95 transition-all disabled:opacity-40 cursor-pointer"
              >
                0
              </button>

              <button
                type="button"
                disabled={lockoutSec > 0 || busy}
                onClick={handleBackspace}
                aria-label="Backspace"
                className="w-16 h-16 rounded-full bg-[#181C22] border border-white/10 flex items-center justify-center text-[#F3F5F7] hover:border-white/30 active:scale-95 transition-all disabled:opacity-40 cursor-pointer"
              >
                <Delete className="w-5 h-5" />
              </button>
            </div>

            <div className="w-full flex items-center justify-between pt-2">
              <button
                type="button"
                onClick={() => setShowForgotModal(true)}
                className="text-xs font-medium text-[#C6FF33] hover:underline cursor-pointer"
              >
                Forgot SEM-PIN?
              </button>
              {enteredPin.length >= 4 && (
                <button
                  type="button"
                  disabled={lockoutSec > 0 || busy}
                  onClick={handleSubmitPin}
                  className="min-h-[38px] px-4 py-1.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-bold hover:bg-[#d4ff66] disabled:opacity-40 cursor-pointer"
                >
                  {busy ? 'Checking...' : 'Unlock'}
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Forgot SEM-PIN Recovery Modal */}
      {showForgotModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-md bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-[#F3F5F7]">Reset Forgotten SEM-PIN</h3>
              <button
                type="button"
                onClick={() => setShowForgotModal(false)}
                className="min-h-[36px] min-w-[36px] rounded-xl flex items-center justify-center text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs text-[#9CA3AF] leading-relaxed">
              Resetting a forgotten SEM-PIN requires verified account re-authentication or your registered device authenticator before replacing the local Keystore hash.
            </p>

            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                  New 4–6 digit SEM-PIN
                </label>
                <input
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={recoveryPin}
                  onChange={(e) => setRecoveryPin(e.target.value.replace(/\D/g, ''))}
                  placeholder="Enter 4–6 digits"
                  className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                  Confirm New SEM-PIN
                </label>
                <input
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={recoveryConfirmPin}
                  onChange={(e) => setRecoveryConfirmPin(e.target.value.replace(/\D/g, ''))}
                  placeholder="Re-enter PIN"
                  className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
            </div>

            {recoveryError && (
              <p className="text-xs text-[#EF4444]">{recoveryError}</p>
            )}

            <div className="space-y-2 pt-1">
              {isFirebaseSignedIn && (
                <button
                  type="button"
                  disabled={recoveryBusy}
                  onClick={handleRecoveryGoogle}
                  className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-bold hover:bg-[#d4ff66] disabled:opacity-50 cursor-pointer"
                >
                  {recoveryBusy ? 'Re-authenticating...' : 'Verify Google Account & Reset PIN'}
                </button>
              )}

              {settings.webAuthnCredentialIdBase64 && (
                <button
                  type="button"
                  disabled={recoveryBusy}
                  onClick={handleRecoveryDevice}
                  className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-semibold text-[#F3F5F7] hover:border-[#C6FF33]/50 disabled:opacity-50 cursor-pointer"
                >
                  Verify Device Biometrics &amp; Reset PIN
                </button>
              )}

              {!isFirebaseSignedIn && !settings.webAuthnCredentialIdBase64 && (
                <div className="p-3 rounded-xl bg-[#FF6B2C]/10 border border-[#FF6B2C]/30 flex items-start gap-2 text-xs text-[#FDBA74]">
                  <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                  <span>
                    Offline local vaults without Google Sign-In or enrolled device biometrics cannot verify identity remotely. Sign in via Google to enable account-verified recovery.
                  </span>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
