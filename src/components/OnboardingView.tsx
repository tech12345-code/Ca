import React, { useEffect, useState } from 'react';
import {
  ShieldCheck,
  ArrowRight,
  Wallet,
  Smartphone,
  Lock,
  CheckCircle2,
  Circle,
} from 'lucide-react';
import { DeviceSecurityCapability, SecurityMethod } from '../types/expense';
import { checkDeviceSecurityCapability, validatePinSetup } from '../lib/appLock';

interface OnboardingViewProps {
  isAuthenticated: boolean;
  budgetCompleted: boolean;
  userDisplayName: string;
  userEmail: string;
  authLoading: boolean;
  authError: string | null;
  onGoogleSignIn: () => void;
  onUseLocalVault: () => void;
  onCompleteSetup: (monthlyBudget: number, currencySymbol: string, displayName: string) => void;
  onCompleteSecuritySetup: (params: {
    method: SecurityMethod;
    pin: string;
    confirmPin: string;
    enableFingerprintQuickUnlock: boolean;
  }) => Promise<{ ok: boolean; error?: string }>;
}

export const OnboardingView: React.FC<OnboardingViewProps> = ({
  isAuthenticated,
  budgetCompleted,
  userDisplayName,
  userEmail,
  authLoading,
  authError,
  onGoogleSignIn,
  onUseLocalVault,
  onCompleteSetup,
  onCompleteSecuritySetup,
}) => {
  const [budgetInput, setBudgetInput] = useState('');
  const [currency, setCurrency] = useState('₹');
  const [nameInput, setNameInput] = useState(userDisplayName || '');
  const [validationError, setValidationError] = useState<string | null>(null);

  // Step 3 Security choice
  const [selectedMethod, setSelectedMethod] = useState<SecurityMethod>('SEM_PIN');
  const [pin, setPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [enableFingerprint, setEnableFingerprint] = useState(false);
  const [securityError, setSecurityError] = useState<string | null>(null);
  const [securityBusy, setSecurityBusy] = useState(false);
  const [capability, setCapability] = useState<DeviceSecurityCapability>({
    platformAuthenticatorAvailable: false,
    reason: 'Checking device authenticator...',
  });

  useEffect(() => {
    checkDeviceSecurityCapability().then((cap) => {
      setCapability(cap);
      setEnableFingerprint(cap.platformAuthenticatorAvailable);
    });
  }, []);

  const handleSubmitBudget = (e: React.FormEvent) => {
    e.preventDefault();
    const parsed = Number(budgetInput);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setValidationError('Enter a valid monthly budget amount greater than 0.');
      return;
    }
    setValidationError(null);
    onCompleteSetup(parsed, currency, nameInput.trim() || userDisplayName || 'Account Holder');
  };

  const handleSubmitSecurity = async (e: React.FormEvent) => {
    e.preventDefault();
    setSecurityError(null);
    if (selectedMethod === 'SEM_PIN') {
      const err = validatePinSetup(pin, confirmPin);
      if (err) {
        setSecurityError(err);
        return;
      }
    }
    setSecurityBusy(true);
    const res = await onCompleteSecuritySetup({
      method: selectedMethod,
      pin,
      confirmPin,
      enableFingerprintQuickUnlock: enableFingerprint,
    });
    setSecurityBusy(false);
    if (!res.ok) {
      setSecurityError(res.error || 'Could not configure App Lock security.');
    }
  };

  return (
    <div className="min-h-screen bg-[#0B0D0F] text-[#F3F5F7] flex items-center justify-center p-6">
      <div className="w-full max-w-5xl grid grid-cols-1 lg:grid-cols-12 gap-10 items-stretch">
        {/* Left Editorial Introduction */}
        <div className="lg:col-span-7 flex flex-col justify-between py-4">
          <div>
            <p className="text-xs font-medium tracking-wider uppercase text-[#C6FF33] mb-4">
              Smart Expense Manager
            </p>
            <h1 className="text-4xl lg:text-5xl font-bold tracking-tight text-[#F3F5F7] leading-[1.1] max-w-xl">
              Precision personal ledger with chronological balance accuracy.
            </h1>
            <p className="mt-5 text-base text-[#9CA3AF] leading-relaxed max-w-xl">
              Built as a production Kotlin &amp; Jetpack Compose Android application paired with this live Firebase-synchronized companion. Protected with Keystore-encrypted SEM-PIN app security, split expense settlements, receipt OCR, and PDF statements.
            </p>
          </div>

          <div className="mt-10 grid grid-cols-1 sm:grid-cols-3 gap-6 pt-8 border-t border-white/10">
            <div>
              <p className="text-sm font-semibold text-[#F3F5F7]">Chronological Math</p>
              <p className="mt-1 text-xs text-[#9CA3AF] leading-relaxed">
                Reorders backdated entries automatically and recalculates every running balance.
              </p>
            </div>
            <div>
              <p className="text-sm font-semibold text-[#F3F5F7]">SEM-PIN Protection</p>
              <p className="mt-1 text-xs text-[#9CA3AF] leading-relaxed">
                Salted PBKDF2 / Android Keystore PIN lock stays strictly on-device, never synced to Firestore.
              </p>
            </div>
            <div>
              <p className="text-sm font-semibold text-[#F3F5F7]">Zero-Trust Isolation</p>
              <p className="mt-1 text-xs text-[#9CA3AF] leading-relaxed">
                User-scoped Firestore rules. Never requests UPI PINs, OTPs, or bank passwords.
              </p>
            </div>
          </div>
        </div>

        {/* Right Setup Card */}
        <div className="lg:col-span-5 bg-[#121519] border border-white/10 rounded-2xl p-7 flex flex-col justify-center">
          {!isAuthenticated ? (
            <div className="space-y-6">
              <div className="w-11 h-11 rounded-xl bg-[#C6FF33]/10 border border-[#C6FF33]/30 flex items-center justify-center text-[#C6FF33]">
                <ShieldCheck className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-2xl font-semibold text-[#F3F5F7]">Welcome</h2>
                <p className="mt-2 text-sm text-[#9CA3AF] leading-relaxed">
                  Sign in with Google to enable isolated Cloud Firestore backup, split group invites, and real-time restoration.
                </p>
              </div>

              {authError && (
                <div className="p-3 rounded-xl bg-[#EF4444]/10 border border-[#EF4444]/30 text-xs text-[#FCA5A5]">
                  {authError}
                </div>
              )}

              <div className="space-y-3">
                <button
                  type="button"
                  onClick={onGoogleSignIn}
                  disabled={authLoading}
                  className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] active:scale-[0.99] transition-all flex items-center justify-center gap-2 disabled:opacity-60 cursor-pointer"
                >
                  <span>{authLoading ? 'Signing in...' : 'Continue with Google'}</span>
                  <ArrowRight className="w-4 h-4" />
                </button>

                <button
                  type="button"
                  onClick={onUseLocalVault}
                  className="w-full min-h-[44px] px-5 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-[#F3F5F7] text-sm font-medium hover:bg-[#1f242c] transition-colors flex items-center justify-center gap-2 cursor-pointer"
                >
                  <Smartphone className="w-4 h-4 text-[#9CA3AF]" />
                  <span>Continue Offline (Local Device Vault)</span>
                </button>
              </div>

              <p className="text-xs text-[#9CA3AF]">
                Offline vault stores data locally on your device and can be backed up once you sign in.
              </p>
            </div>
          ) : !budgetCompleted ? (
            <form onSubmit={handleSubmitBudget} className="space-y-5">
              <div className="w-11 h-11 rounded-xl bg-[#C6FF33]/10 border border-[#C6FF33]/30 flex items-center justify-center text-[#C6FF33]">
                <Wallet className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-2xl font-semibold text-[#F3F5F7]">Set Monthly Budget</h2>
                <p className="mt-1 text-sm text-[#9CA3AF]">
                  {userEmail ? `Signed in as ${userEmail}` : 'Local offline vault active'}. This initial amount becomes both your monthly budget and opening balance.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Your Name
                </label>
                <input
                  type="text"
                  value={nameInput}
                  onChange={(e) => setNameInput(e.target.value)}
                  placeholder="e.g. Ajitesh Dwivedi"
                  className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>

              <div className="grid grid-cols-4 gap-3">
                <div className="col-span-1">
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Currency
                  </label>
                  <select
                    value={currency}
                    onChange={(e) => setCurrency(e.target.value)}
                    className="w-full min-h-[44px] px-2.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  >
                    <option value="₹">₹ INR</option>
                    <option value="$">$ USD</option>
                    <option value="€">€ EUR</option>
                    <option value="£">£ GBP</option>
                  </select>
                </div>
                <div className="col-span-3">
                  <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                    Monthly Budget &amp; Opening Balance
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    required
                    value={budgetInput}
                    onChange={(e) => setBudgetInput(e.target.value)}
                    placeholder="e.g. 25000"
                    className="w-full min-h-[44px] px-3.5 py-2.5 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                  />
                </div>
              </div>

              {validationError && (
                <p className="text-xs text-[#EF4444]">{validationError}</p>
              )}

              <button
                type="submit"
                className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] transition-all flex items-center justify-center gap-2 cursor-pointer"
              >
                <span>Save Budget &amp; Choose App Lock</span>
                <ArrowRight className="w-4 h-4" />
              </button>
            </form>
          ) : (
            <form onSubmit={handleSubmitSecurity} className="space-y-4">
              <div className="w-11 h-11 rounded-xl bg-[#C6FF33]/10 border border-[#C6FF33]/30 flex items-center justify-center text-[#C6FF33]">
                <Lock className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-2xl font-semibold text-[#F3F5F7]">
                  Choose App Lock Security
                </h2>
                <p className="mt-1 text-xs text-[#9CA3AF] leading-relaxed">
                  Protect Smart Expense Manager before opening your dashboard. SEM-PIN is hashed locally and never stored in Firestore.
                </p>
              </div>

              <div className="space-y-2">
                {[
                  {
                    id: 'SEM_PIN' as const,
                    title: 'SEM-PIN (Recommended)',
                    desc: '4–6 digit numeric PIN stored securely on this device.',
                    disabled: false,
                  },
                  {
                    id: 'DEVICE_LOCK' as const,
                    title: 'Mobile Screen Lock / Biometrics',
                    desc: capability.platformAuthenticatorAvailable
                      ? 'Verify with OS Fingerprint, Face, or Device Screen Lock.'
                      : capability.reason,
                    disabled: !capability.platformAuthenticatorAvailable,
                  },
                  {
                    id: 'NONE' as const,
                    title: 'No App Lock (Optional)',
                    desc: 'Opens dashboard immediately without a secondary lock screen.',
                    disabled: false,
                  },
                ].map((opt) => {
                  const selected = selectedMethod === opt.id;
                  return (
                    <button
                      key={opt.id}
                      type="button"
                      disabled={opt.disabled}
                      onClick={() => {
                        setSecurityError(null);
                        setSelectedMethod(opt.id);
                      }}
                      className={`w-full text-left p-3 rounded-xl border flex items-start justify-between gap-3 cursor-pointer disabled:opacity-45 ${
                        selected
                          ? 'bg-[#181C22] border-[#C6FF33]'
                          : 'bg-[#0B0D0F] border-white/10 hover:border-white/25'
                      }`}
                    >
                      <div>
                        <p className="text-xs font-semibold text-[#F3F5F7]">{opt.title}</p>
                        <p className="text-[11px] text-[#9CA3AF] mt-0.5">{opt.desc}</p>
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

              {selectedMethod === 'SEM_PIN' && (
                <div className="space-y-2.5 pt-1">
                  <div>
                    <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                      Create 4–6 digit SEM-PIN
                    </label>
                    <input
                      type="password"
                      inputMode="numeric"
                      maxLength={6}
                      required
                      value={pin}
                      onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
                      placeholder="Enter 4–6 numeric digits"
                      className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[#9CA3AF] mb-1">
                      Confirm SEM-PIN
                    </label>
                    <input
                      type="password"
                      inputMode="numeric"
                      maxLength={6}
                      required
                      value={confirmPin}
                      onChange={(e) => setConfirmPin(e.target.value.replace(/\D/g, ''))}
                      placeholder="Re-enter SEM-PIN"
                      className="w-full min-h-[42px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                    />
                  </div>
                  {capability.platformAuthenticatorAvailable && (
                    <label className="flex items-center gap-2 text-xs text-[#F3F5F7] cursor-pointer pt-1">
                      <input
                        type="checkbox"
                        checked={enableFingerprint}
                        onChange={(e) => setEnableFingerprint(e.target.checked)}
                        className="accent-[#C6FF33]"
                      />
                      <span>Also allow Fingerprint quick unlock</span>
                    </label>
                  )}
                </div>
              )}

              {selectedMethod === 'NONE' && (
                <p className="text-[11px] text-[#FF6B2C]">
                  Note: Without App Lock, balances are visible immediately when opening the app. You can enable SEM-PIN anytime in Settings.
                </p>
              )}

              {securityError && (
                <p className="text-xs text-[#EF4444]">{securityError}</p>
              )}

              <button
                type="submit"
                disabled={securityBusy}
                className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] transition-all flex items-center justify-center gap-2 disabled:opacity-50 cursor-pointer"
              >
                <span>
                  {securityBusy
                    ? 'Saving Security...'
                    : selectedMethod === 'SEM_PIN'
                      ? 'Activate SEM-PIN & Open Dashboard'
                      : selectedMethod === 'DEVICE_LOCK'
                        ? 'Verify Device Lock & Open Dashboard'
                        : 'Continue Without App Lock'}
                </span>
                <ArrowRight className="w-4 h-4" />
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};
