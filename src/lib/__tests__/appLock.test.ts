import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_SECURITY_SETTINGS,
  canDispatchDeepLink,
  changeExistingSemPin,
  computeRetryDelayMillis,
  createOrReplaceSemPinSettings,
  generateSaltBase64,
  hashSemPin,
  isLockActiveOnColdStart,
  remainingLockoutSeconds,
  resetForgottenPinAfterVerifiedIdentity,
  shouldLockOnResume,
  validatePinFormat,
  validatePinSetup,
  verifyAndAdvanceLockout,
  verifyPinConstantTime,
} from '../appLock';

test('SEM-PIN format requires 4 to 6 numeric digits', () => {
  assert.equal(validatePinFormat('1234'), null);
  assert.equal(validatePinFormat('12345'), null);
  assert.equal(validatePinFormat('098765'), null);

  assert.notEqual(validatePinFormat('123'), null);
  assert.notEqual(validatePinFormat('1234567'), null);
  assert.notEqual(validatePinFormat('12a4'), null);
  assert.notEqual(validatePinFormat(''), null);
});

test('SEM-PIN double entry rejects mismatch', () => {
  assert.equal(validatePinSetup('5489', '5489'), null);
  assert.notEqual(validatePinSetup('5489', '5480'), null);
});

test('PBKDF2-SHA256 hashing is salted and verifies constant-time without plaintext', async () => {
  const salt1 = generateSaltBase64();
  const salt2 = generateSaltBase64();
  const hash1 = await hashSemPin('2468', salt1);
  const hash2 = await hashSemPin('2468', salt2);

  assert.notEqual(hash1, hash2);
  assert.notEqual(hash1, '2468');
  assert.equal(await verifyPinConstantTime('2468', salt1, hash1), true);
  assert.equal(await verifyPinConstantTime('2469', salt1, hash1), false);
});

test('Retry delay escalates on repeated wrong SEM-PIN attempts', async () => {
  const created = await createOrReplaceSemPinSettings(
    DEFAULT_SECURITY_SETTINGS,
    '9182',
    '9182',
    false
  );
  assert.ok(created.settings);
  let state = created.settings!;
  const t0 = 1_000_000;

  assert.equal(computeRetryDelayMillis(1), 0);
  assert.equal(computeRetryDelayMillis(2), 0);
  assert.equal(computeRetryDelayMillis(3), 15_000);
  assert.equal(computeRetryDelayMillis(4), 30_000);
  assert.equal(computeRetryDelayMillis(5), 60_000);

  const attempt1 = await verifyAndAdvanceLockout(state, '0000', t0);
  assert.equal(attempt1.ok, false);
  state = attempt1.updated;
  assert.equal(state.failedAttempts, 1);

  const attempt2 = await verifyAndAdvanceLockout(state, '0000', t0 + 100);
  assert.equal(attempt2.ok, false);
  state = attempt2.updated;
  assert.equal(state.failedAttempts, 2);

  const attempt3 = await verifyAndAdvanceLockout(state, '0000', t0 + 200);
  assert.equal(attempt3.ok, false);
  state = attempt3.updated;
  assert.equal(state.failedAttempts, 3);
  assert.equal(remainingLockoutSeconds(state.lockoutUntilMillis, t0 + 200), 15);

  // Even the right PIN cannot unlock while lockout countdown is active
  const blockedCorrect = await verifyAndAdvanceLockout(state, '9182', t0 + 5_000);
  assert.equal(blockedCorrect.ok, false);

  // Unlocks once lockout expires and resets attempts
  const unlocked = await verifyAndAdvanceLockout(state, '9182', t0 + 16_000);
  assert.equal(unlocked.ok, true);
  assert.equal(unlocked.updated.failedAttempts, 0);
  assert.equal(unlocked.updated.lockoutUntilMillis, 0);
});

test('Changing PIN verifies current PIN and Forgot PIN requires verified identity proof', async () => {
  const created = await createOrReplaceSemPinSettings(
    DEFAULT_SECURITY_SETTINGS,
    '1357',
    '1357',
    false
  );
  const initial = created.settings!;

  const badChange = await changeExistingSemPin(initial, '9999', '2468', '2468');
  assert.ok(badChange.error);

  const goodChange = await changeExistingSemPin(initial, '1357', '2468', '2468');
  assert.ok(goodChange.settings);
  assert.equal(
    (await verifyAndAdvanceLockout(goodChange.settings!, '2468')).ok,
    true
  );

  // Forgot PIN blocked if identityVerified === false
  const blockedReset = await resetForgottenPinAfterVerifiedIdentity(
    goodChange.settings!,
    false,
    '7777',
    '7777'
  );
  assert.ok(blockedReset.error);

  const verifiedReset = await resetForgottenPinAfterVerifiedIdentity(
    goodChange.settings!,
    true,
    '7777',
    '7777'
  );
  assert.ok(verifiedReset.settings);
  assert.equal(
    (await verifyAndAdvanceLockout(verifiedReset.settings!, '7777')).ok,
    true
  );
});

test('Cold start lock, auto-lock timeout, and deep-link gating policies hold', async () => {
  const created = await createOrReplaceSemPinSettings(
    DEFAULT_SECURITY_SETTINGS,
    '4321',
    '4321',
    false
  );
  const settings = { ...created.settings!, autoLockTimeoutSeconds: 30 as const };

  assert.equal(isLockActiveOnColdStart(settings), true);
  assert.equal(shouldLockOnResume(settings, 10_000, 20_000), false);
  assert.equal(shouldLockOnResume(settings, 10_000, 40_000), true);

  assert.equal(canDispatchDeepLink(settings, false), false);
  assert.equal(canDispatchDeepLink(settings, true), true);

  const disabled = { ...settings, appLockEnabled: false, method: 'NONE' as const };
  assert.equal(isLockActiveOnColdStart(disabled), false);
  assert.equal(shouldLockOnResume(disabled, 10_000, 999_999), false);
  assert.equal(canDispatchDeepLink(disabled, false), true);
});
