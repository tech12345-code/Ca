import React, { useState } from 'react';
import { CloudUpload, CloudDownload, LogOut, Bell, ShieldCheck } from 'lucide-react';
import {
  AppRoute,
  NotificationPreferences,
  SecuritySettings,
  UserProfile,
} from '../types/expense';

interface SettingsViewProps {
  profile: UserProfile;
  isFirebaseSignedIn: boolean;
  securitySettings: SecuritySettings;
  syncStatusMessage: string | null;
  onUpdateBudget: (newBudget: number) => void;
  onUpdatePreferences: (prefs: NotificationPreferences) => void;
  onBackupNow: () => void;
  onRestoreCloud: () => void;
  onRequestBrowserPushPermission: () => void;
  onNavigate: (route: AppRoute) => void;
  onSignOut: () => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({
  profile,
  isFirebaseSignedIn,
  securitySettings,
  syncStatusMessage,
  onUpdateBudget,
  onUpdatePreferences,
  onBackupNow,
  onRestoreCloud,
  onRequestBrowserPushPermission,
  onNavigate,
  onSignOut,
}) => {
  const [budgetInput, setBudgetInput] = useState(String(profile.monthlyBudget));
  const [budgetSaved, setBudgetSaved] = useState(false);
  const prefs = profile.preferences;

  const togglePref = (key: keyof NotificationPreferences, value: boolean) => {
    onUpdatePreferences({
      ...prefs,
      [key]: value,
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-[#F3F5F7]">
          Settings
        </h1>
        <p className="text-sm text-[#9CA3AF] mt-1">
          Manage SEM App Lock security, monthly budget, Firebase Cloud backup, and FCM notification preferences.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Column */}
        <div className="lg:col-span-6 space-y-6">
          {/* SEM App Lock Security */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-2.5">
                <ShieldCheck className="w-5 h-5 text-[#C6FF33]" />
                <h2 className="text-base font-semibold text-[#F3F5F7]">
                  SEM App Lock Security
                </h2>
              </div>
              <span
                className={`text-xs font-medium ${
                  securitySettings.appLockEnabled ? 'text-[#2CE59B]' : 'text-[#FF6B2C]'
                }`}
              >
                {securitySettings.appLockEnabled
                  ? securitySettings.method.replace('_', ' ')
                  : 'OFF'}
              </span>
            </div>
            <p className="text-xs text-[#9CA3AF] leading-relaxed">
              Configure your 4–6 digit SEM-PIN, device screen lock / biometrics, auto-lock timing, and verified PIN recovery options.
            </p>
            <button
              type="button"
              onClick={() => onNavigate('security')}
              className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-sm font-semibold hover:bg-[#d4ff66] cursor-pointer"
            >
              Manage SEM-PIN &amp; App Lock
            </button>
          </div>

          {/* User Profile & Monthly Budget */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Profile &amp; Monthly Budget
            </h2>
            <div className="text-sm">
              <p className="font-medium text-[#F3F5F7]">{profile.displayName}</p>
              <p className="text-xs text-[#9CA3AF] mt-0.5">
                {profile.email || 'Offline Local Vault'} · Opening Balance:{' '}
                {profile.currencySymbol}
                {profile.openingBalance.toLocaleString()}
              </p>
            </div>

            <div className="pt-2 flex items-end gap-3">
              <div className="flex-1">
                <label className="block text-xs font-medium text-[#9CA3AF] mb-1.5">
                  Monthly Budget ({profile.currencySymbol})
                </label>
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  value={budgetInput}
                  onChange={(e) => {
                    setBudgetInput(e.target.value);
                    setBudgetSaved(false);
                  }}
                  className="w-full min-h-[44px] px-3.5 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-sm font-mono-num text-[#F3F5F7] focus:outline-none focus:border-[#C6FF33]"
                />
              </div>
              <button
                type="button"
                onClick={() => {
                  const num = Number(budgetInput);
                  if (Number.isFinite(num) && num >= 0) {
                    onUpdateBudget(num);
                    setBudgetSaved(true);
                  }
                }}
                className="min-h-[44px] px-5 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] font-semibold text-sm hover:bg-[#d4ff66] cursor-pointer whitespace-nowrap"
              >
                {budgetSaved ? 'Saved' : 'Update Budget'}
              </button>
            </div>
          </div>

          {/* Cloud Backup & Restore */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Firebase Cloud Backup &amp; Statement Export
            </h2>
            <p className="text-xs text-[#9CA3AF] leading-relaxed">
              {isFirebaseSignedIn
                ? `Authenticated with Google. Last backup: ${
                    profile.lastBackupAt
                      ? new Date(profile.lastBackupAt).toLocaleString()
                      : 'Automatic sync active'
                  }`
                : 'Currently in Offline Local Vault mode. Sign in with Google to synchronize to Cloud Firestore.'}
            </p>
            {syncStatusMessage && (
              <p className="text-xs text-[#C6FF33]">{syncStatusMessage}</p>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <button
                type="button"
                disabled={!isFirebaseSignedIn}
                onClick={onBackupNow}
                className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:bg-[#20262e] disabled:opacity-40 inline-flex items-center justify-center gap-2 cursor-pointer"
              >
                <CloudUpload className="w-4 h-4 text-[#C6FF33]" />
                <span>Cloud Backup Now</span>
              </button>
              <button
                type="button"
                disabled={!isFirebaseSignedIn}
                onClick={onRestoreCloud}
                className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:bg-[#20262e] disabled:opacity-40 inline-flex items-center justify-center gap-2 cursor-pointer"
              >
                <CloudDownload className="w-4 h-4 text-[#2CE59B]" />
                <span>Restore Backup</span>
              </button>
            </div>

            <button
              type="button"
              onClick={() => onNavigate('pdf')}
              className="w-full min-h-[42px] px-4 py-2 rounded-xl bg-[#0B0D0F] border border-white/10 text-xs text-[#F3F5F7] hover:border-[#C6FF33]/40 cursor-pointer"
            >
              Export PDF Statement
            </button>
          </div>

          {/* Privacy Policy, About & Sign Out */}
          <div className="bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-4">
            <h2 className="text-base font-semibold text-[#F3F5F7]">
              Privacy Policy &amp; Application
            </h2>
            <p className="text-xs text-[#9CA3AF] leading-relaxed">
              Smart Expense Manager enforces per-user data isolation via Firestore Security Rules (`users/&#123;uid&#125;`). Sensitive credentials including SEM-PIN, UPI PINs, OTPs, and banking passwords are never transmitted to Firestore or logged.
            </p>
            <button
              type="button"
              onClick={onSignOut}
              className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-[#EF4444]/15 border border-[#EF4444]/30 text-[#FCA5A5] text-sm font-medium hover:bg-[#EF4444]/25 inline-flex items-center justify-center gap-2 cursor-pointer"
            >
              <LogOut className="w-4 h-4" />
              <span>Logout</span>
            </button>
          </div>
        </div>

        {/* Right Column: Push Notifications (FCM) */}
        <div className="lg:col-span-6 bg-[#121519] border border-white/10 rounded-2xl p-6 space-y-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Bell className="w-4 h-4 text-[#C6FF33]" />
              <h2 className="text-base font-semibold text-[#F3F5F7]">
                Push Notifications (FCM)
              </h2>
            </div>
            <button
              type="button"
              onClick={onRequestBrowserPushPermission}
              className="min-h-[36px] px-3 py-1.5 rounded-lg bg-[#181C22] border border-white/10 text-xs font-medium text-[#C6FF33] hover:bg-[#20262e] cursor-pointer"
            >
              Enable Alerts
            </button>
          </div>

          <div className="divide-y divide-white/8">
            <ToggleRow
              label="Expense reminders"
              description="Daily reminder to record cash and UPI transactions."
              checked={prefs.expenseReminders}
              onChange={(v) => togglePref('expenseReminders', v)}
            />
            {prefs.expenseReminders && (
              <div className="py-3 flex items-center justify-between gap-4">
                <span className="text-xs text-[#9CA3AF]">Reminder time</span>
                <input
                  type="time"
                  value={`${String(prefs.reminderHour).padStart(2, '0')}:${String(prefs.reminderMinute).padStart(2, '0')}`}
                  onChange={(e) => {
                    const [h, m] = e.target.value.split(':').map(Number);
                    if (!Number.isNaN(h) && !Number.isNaN(m)) {
                      onUpdatePreferences({
                        ...prefs,
                        reminderHour: h,
                        reminderMinute: m,
                      });
                    }
                  }}
                  className="px-3 py-1.5 rounded-lg bg-[#0B0D0F] border border-white/10 text-xs font-mono-num text-[#F3F5F7]"
                />
              </div>
            )}
            <ToggleRow
              label="Transaction confirmations"
              description='Prompts "Where did you spend this money?" for detected or imported payments.'
              checked={prefs.transactionConfirmations}
              onChange={(v) => togglePref('transactionConfirmations', v)}
            />
            <ToggleRow
              label="Budget alerts (50%, 75%, 90%, 100%)"
              description="Deduplicated alerts as monthly spending crosses budget thresholds."
              checked={prefs.budgetAlerts}
              onChange={(v) => togglePref('budgetAlerts', v)}
            />
            <ToggleRow
              label="Daily summaries"
              description="Optional evening recap of daily spending and remaining budget."
              checked={prefs.dailySummary}
              onChange={(v) => togglePref('dailySummary', v)}
            />
            <ToggleRow
              label="Weekly reports"
              description="Weekly summary of total income, expenses, and highest category."
              checked={prefs.weeklyReport}
              onChange={(v) => togglePref('weeklyReport', v)}
            />
            <ToggleRow
              label="Cloud backup alerts"
              description="Notify when Firestore cloud backup or restoration completes or fails."
              checked={prefs.cloudBackupAlerts}
              onChange={(v) => togglePref('cloudBackupAlerts', v)}
            />
            <ToggleRow
              label={`Quiet hours (${prefs.quietStartHour}:00 – ${prefs.quietEndHour}:00)`}
              description="Suppresses non-critical reminders overnight."
              checked={prefs.quietHoursEnabled}
              onChange={(v) => togglePref('quietHoursEnabled', v)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

interface ToggleRowProps {
  label: string;
  description: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}

const ToggleRow: React.FC<ToggleRowProps> = ({
  label,
  description,
  checked,
  onChange,
}) => (
  <div className="py-3.5 flex items-center justify-between gap-4">
    <div>
      <p className="text-sm font-medium text-[#F3F5F7]">{label}</p>
      <p className="text-xs text-[#9CA3AF] mt-0.5">{description}</p>
    </div>
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`w-11 h-6 rounded-full transition-colors p-0.5 shrink-0 cursor-pointer ${
        checked ? 'bg-[#C6FF33]' : 'bg-[#262B33]'
      }`}
    >
      <span
        className={`block w-5 h-5 rounded-full bg-[#0B0D0F] transition-transform ${
          checked ? 'translate-x-5' : 'translate-x-0'
        }`}
      />
    </button>
  </div>
);
