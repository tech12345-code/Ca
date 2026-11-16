import React, { useEffect, useState } from 'react';
import {
  ArrowLeft,
  CheckCircle2,
  Circle,
  Lock,
  ShieldCheck,
  X,
} from 'lucide-react';
import {
  AutoLockTimeoutSeconds,
  DeviceSecurityCapability,
  SecurityMethod,
  SecuritySettings,
} from '../types/expense';
import { checkDeviceSecurityCapability } from '../lib/appLock';

interface SecuritySettingsViewProps {
  userEmail: string;
  settings: SecuritySettings;
  onBack: () => void;
  onToggleAppLock: (enabled: boolean) => Promise<{ ok: boolean; error?: string }>;
  onSelectMethod: (method: SecurityMethod) => Promise<{ ok: boolean; error?: string }>;
  onCreateOrChangePin: (
    currentPin: string,
    newPin: string,
    confirmPin: string
  ) => Promise<{ ok: boolean; error?: string }>;
  onToggleFingerprintQuickUnlock: (enabled: boolean) => Promise<{ ok: boolean; error?: string }>;
  onSelectTimeout: (seconds: AutoLockTimeoutSeconds) => void;
  onToggleLockOnClose: (enabled: boolean) => void;
  onLockNow: () => void;
}

const METHODS: Array<{ id: SecurityMethod; label: string; description: string }> = [
  {
    id: 'SEM_PIN',
    label: 'SEM-PIN (4–6 Digit App PIN)',
    description:
      'Stored locally using salted PBKDF2-SHA256 derivation. Never uploaded to Firebase Firestore or logged.',
  },
  {
    id: 'DEVICE_LOCK',
    label: 'Phone / Device Screen Lock & Biometrics',
    description:
      'Uses WebAuthn Platform Authenticator (Web) and official Android BiometricPrompt + DEVICE_CREDENTIAL (Android).',
  },
  {
    id: 'NONE',
    label: 'No App Lock',
    description: 'Opens Smart Expense Manager directly without a secondary lock screen.',
  },
];

const TIMEOUT_OPTIONS: Array<{ seconds: AutoLockTimeoutSeconds; label: string }> = [
  { seconds: 0, label: 'Immediately' },
  { seconds: 30, label: '30 seconds' },
  { seconds: 60, label: '1 minute' },
  { seconds: 300, label: '5 minutes' },
];

export const SecuritySettingsView: React.FC<SecuritySettingsViewProps> = ({
  userEmail,
  settings,
  onBack,
  onToggleAppLock,
  onSelectMethod,
  onCreateOrChangePin,
  onToggleFingerprintQuickUnlock,
  onSelectTimeout,
  onToggleLockOnClose,
  onLockNow,
}) => {
  const [capability, setCapability] = useState<DeviceSecurityCapability>({
    platformAuthenticatorAvailable: false,
    reason: 'Checking device capabilities...',
  });
  const [statusFeedback, setStatusFeedback] = useState<{
    text: string;
    isError: boolean;
  } | null>(null);
  const [showPinModal, setShowPinModal] = useState(false);
  const [currentPin, setCurrentPin] = useState('');
  const [newPin, setNewPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [pinError, setPinError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    checkDeviceSecurityCapability().then(setCapability);
  }, []);

  const handleMethodClick = async (method: SecurityMethod) => {
    setStatusFeedback(null);
    if (method === 'SEM_PIN' && !settings.hasSemPinSet) {
      setShowPinModal(true);
      return;
    }
    const res = await onSelectMethod(method);
    if (!res.ok) {
      setStatusFeedback({ text: res.error || 'Could not switch method.', isError: true });
    } else {
      setStatusFeedback({ text: `Active security method: ${method.replace('_', ' ')}`, isError: false });
    }
  };

  const handleSavePinModal = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setPinError(null);
    const res = await onCreateOrChangePin(currentPin, newPin, confirmPin);
    setBusy(false);
    if (!res.ok) {
      setPinError(res.error || 'Failed to save SEM-PIN.');
      return;
    }
    setCurrentPin('');
    setNewPin('');
    setConfirmPin('');
    setShowPinModal(false);
    setStatusFeedback({ text: 'SEM-PIN updated securely.', isError: false });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={onBack}
            className="min-h-[40px] min-w-[40px] rounded-xl bg-[#121519] border border-white/10 flex items-center justify-center text-[#F3F5F7] hover:border-[#C6FF33]/50 cursor-pointer"
            aria-label="Back to Settings"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div>
            <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-[#F3F5F7]">
              SEM App Lock &amp; Security
            </h1>
            <p className="text-xs text-[#9CA3AF] mt-0.5">
              PBKDF2 local encryption · Zero PIN sync to Firestore · Context-gated deep links
            </p>
          </div>
        </div>

        {settings.appLockEnabled && settings.method !== 'NONE' && (
          <button
            type="button"
            onClick={onLockNow}
            className="min-h-[42px] px-4 py-2 rounded-xl bg-[#181C22] border border-[#C6FF33]/40 text-xs font-semibold text-[#C6FF33] hover:bg-[#C6FF33] hover:text-[#0B0D0F] inline-flex items-center gap-2 cursor-pointer"
          >
            <Lock className="w-3.5 h-3.5" />
            <span>Lock App Now</span>
          </button>
        )}
      </div>

      {statusFeedback && (
        <div
          className={`p-3.5 rounded-xl border text-xs ${
            statusFeedback.isError
              ? 'bg-[#EF4444]/10 border-[#EF4444]/30 text-[#FCA5A5]'
              : 'bg-[#2CE59B]/10 border-[#2CE59B]/30 text-[#2CE59B]'
          }`}
        >
          {statusFeedback.text}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-6 space-y-6">
          {/* App Lock Toggle & Method */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-2.5">
                <ShieldCheck className="w-5 h-5 text-[#C6FF33]" />
                <div>
                  <h2 className="text-base font-semibold text-[#F3F5F7]">
                    App Lock Protection
                  </h2>
                  <p className="text-xs text-[#9CA3AF]">
                    {settings.appLockEnabled
                      ? `Active (${settings.method.replace('_', ' ')})`
                      : 'App Lock is currently OFF'}
                  </p>
                </div>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={settings.appLockEnabled}
                onClick={async () => {
                  const res = await onToggleAppLock(!settings.appLockEnabled);
                  if (!res.ok) {
                    if (!settings.hasSemPinSet) {
                      setShowPinModal(true);
                    } else {
                      setStatusFeedback({ text: res.error || 'Could not toggle App Lock.', isError: true });
                    }
                  }
                }}
                className={`w-11 h-6 rounded-full transition-colors p-0.5 shrink-0 cursor-pointer ${
                  settings.appLockEnabled ? 'bg-[#C6FF33]' : 'bg-[#262B33]'
                }`}
              >
                <span
                  className={`block w-5 h-5 rounded-full bg-[#0B0D0F] transition-transform ${
                    settings.appLockEnabled ? 'translate-x-5' : 'translate-x-0'
                  }`}
                />
              </button>
            </div>

            <div className="space-y-2.5 pt-2">
              <p className="text-xs font-medium text-[#9CA3AF]">
                Primary Security Method (1 active at a time)
              </p>
              {METHODS.map((m) => {
                const selected = settings.method === m.id;
                const disabled =
                  m.id === 'DEVICE_LOCK' && !capability.platformAuthenticatorAvailable;
                return (
                  <button
                    key={m.id}
                    type="button"
                    disabled={disabled}
                    onClick={() => handleMethodClick(m.id)}
                    className={`w-full text-left p-3.5 rounded-xl border transition-colors flex items-start justify-between gap-3 cursor-pointer disabled:opacity-45 ${
                      selected
                        ? 'bg-[#181C22] border-[#C6FF33]'
                        : 'bg-[#0B0D0F] border-white/10 hover:border-white/25'
                    }`}
                  >
                    <div>
                      <p className="text-sm font-medium text-[#F3F5F7]">{m.label}</p>
                      <p className="text-xs text-[#9CA3AF] mt-1">
                        {disabled ? capability.reason : m.description}
                      </p>
                    </div>
                    {selected ? (
                      <CheckCircle2 className="w-4 h-4 text-[#C6FF33] shrink-0 mt-0.5" />
                    ) : (
                      <Circle className="w-4 h-4 text-[#6B7280] shrink-0 mt-0.5" />
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        <div className="lg:col-span-6 space-y-6">
          {/* SEM-PIN & Fingerprint */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              SEM-PIN &amp; Biometrics
            </h2>
            <button
              type="button"
              onClick={() => setShowPinModal(true)}
              className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              {settings.hasSemPinSet ? 'Change SEM-PIN' : 'Create 4–6 Digit SEM-PIN'}
            </button>

            <div className="pt-2 flex items-center justify-between gap-4 border-t border-white/10">
              <div>
                <p className="text-sm font-medium text-[#F3F5F7]">
                  Enable Fingerprint / Biometric Quick Unlock
                </p>
                <p className="text-xs text-[#9CA3AF] mt-0.5">
                  {capability.platformAuthenticatorAvailable
                    ? 'Allow platform biometric check alongside SEM-PIN.'
                    : capability.reason}
                </p>
              </div>
              <button
                type="button"
                role="switch"
                disabled={!capability.platformAuthenticatorAvailable || settings.method !== 'SEM_PIN'}
                aria-checked={settings.fingerprintQuickUnlockEnabled}
                onClick={async () => {
                  const res = await onToggleFingerprintQuickUnlock(
                    !settings.fingerprintQuickUnlockEnabled
                  );
                  if (!res.ok) {
                    setStatusFeedback({ text: res.error || 'Biometric update failed.', isError: true });
                  }
                }}
                className={`w-11 h-6 rounded-full transition-colors p-0.5 shrink-0 cursor-pointer disabled:opacity-35 ${
                  settings.fingerprintQuickUnlockEnabled ? 'bg-[#C6FF33]' : 'bg-[#262B33]'
                }`}
              >
                <span
                  className={`block w-5 h-5 rounded-full bg-[#0B0D0F] transition-transform ${
                    settings.fingerprintQuickUnlockEnabled ? 'translate-x-5' : 'translate-x-0'
                  }`}
                />
              </button>
            </div>
          </div>

          {/* Auto-lock & Recovery */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Auto-Lock Timing &amp; Recovery
            </h2>
            <div>
              <p className="text-xs text-[#9CA3AF] mb-2">
                Auto-lock timeout after switching tabs or backgrounding:
              </p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {TIMEOUT_OPTIONS.map((opt) => (
                  <button
                    key={opt.seconds}
                    type="button"
                    onClick={() => onSelectTimeout(opt.seconds)}
                    className={`min-h-[38px] px-3 py-1.5 rounded-xl text-xs font-medium border cursor-pointer ${
                      settings.autoLockTimeoutSeconds === opt.seconds
                        ? 'bg-[#C6FF33] border-[#C6FF33] text-[#0B0D0F] font-semibold'
                        : 'bg-[#0B0D0F] border-white/10 text-[#9CA3AF] hover:text-white'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex items-center justify-between gap-4 pt-2 border-t border-white/10">
              <div>
                <p className="text-sm font-medium text-[#F3F5F7]">
                  Lock immediately when app closes
                </p>
                <p className="text-xs text-[#9CA3AF]">
                  Re-opening or reloading requires SEM-PIN / device verification before showing the dashboard.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={settings.lockOnAppClose}
                onClick={() => onToggleLockOnClose(!settings.lockOnAppClose)}
                className={`w-11 h-6 rounded-full transition-colors p-0.5 shrink-0 cursor-pointer ${
                  settings.lockOnAppClose ? 'bg-[#C6FF33]' : 'bg-[#262B33]'
                }`}
              >
                <span
                  className={`block w-5 h-5 rounded-full bg-[#0B0D0F] transition-transform ${
                    settings.lockOnAppClose ? 'translate-x-5' : 'translate-x-0'
                  }`}
                />
              </button>
            </div>

            <div className="pt-2 border-t border-white/10 text-xs text-[#9CA3AF] leading-relaxed">
              Recovery options: If you forget your SEM-PIN, tap &ldquo;Forgot SEM-PIN?&rdquo; on the lock screen to verify your identity via Google account re-authentication ({userEmail || 'when signed in'}) or enrolled biometrics.
            </div>
          </div>
        </div>
      </div>

      {/* Create / Change SEM-PIN Modal */}
      {showPinModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-md bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-semibold text-[#F3F5F7]">
                {settings.hasSemPinSet ? 'Change SEM-PIN' : 'Create 4–6 Digit SEM-PIN'}
              </h3>
              <button
                type="button"
                onClick={() => setShowPinModal(false)}
                className="min-h-[36px] min-w-[36px] rounded-xl flex items-center justify-center text-[#9CA3AF] hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSavePinModal} className="space-y-3">
              {settings.hasSemPinSet && (
                <div>
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                    Current SEM-PIN
                  </label>
                  <input
                    type="password"
                    inputMode="numeric"
                    maxLength={6}
                    required
                    value={currentPin}
                    onChange={(e) => setCurrentPin(e.target.value.replace(/\D/g, ''))}
                    className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              )}
              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                  New 4–6 digit SEM-PIN
                </label>
                <input
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  required
                  value={newPin}
                  onChange={(e) => setNewPin(e.target.value.replace(/\D/g, ''))}
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
                  required
                  value={confirmPin}
                  onChange={(e) => setConfirmPin(e.target.value.replace(/\D/g, ''))}
                  placeholder="Re-enter exact PIN"
                  className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              {pinError && <p className="text-xs text-[#EF4444]">{pinError}</p>}
              <button
                type="submit"
                disabled={busy}
                className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] disabled:opacity-50 cursor-pointer"
              >
                {busy ? 'Encrypting & Saving...' : 'Save SEM-PIN'}
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
