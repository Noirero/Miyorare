import test from 'node:test';
import assert from 'node:assert/strict';
import { BrowserAuthStore, AuthStoreError, challengeForVerifier } from './store.mjs';

const verifier = 'v'.repeat(64);
const transactionId = 't'.repeat(48);

function readyStore() {
  let now = 1_000_000;
  const store = new BrowserAuthStore(() => now);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  const tx = store.consumeState(state);
  store.complete(tx.transactionId, {
    accessToken: 'temporary-access-token',
    tokenExpiresAt: now + 3_600_000,
    account: { email: 'reader@example.com', display_name: 'Reader', photo_url: null },
  });
  return { store, advance: (ms) => { now += ms; } };
}

test('pending transaction does not reveal a code before OAuth callback', () => {
  const store = new BrowserAuthStore(() => 1000);
  store.begin(transactionId, challengeForVerifier(verifier));
  assert.deepEqual(store.poll(transactionId, verifier), { status: 'pending' });
});

test('wrong proof cannot poll or exchange a transaction', () => {
  const { store } = readyStore();
  assert.throws(() => store.poll(transactionId, 'x'.repeat(64)), AuthStoreError);
});

test('one-time app code is single-use', () => {
  const { store } = readyStore();
  const poll = store.poll(transactionId, verifier);
  assert.equal(poll.status, 'ready');
  const grant = store.exchange(poll.code, transactionId, verifier);
  assert.equal(grant.accessToken, 'temporary-access-token');
  assert.throws(() => store.exchange(poll.code, transactionId, verifier), AuthStoreError);
});

test('OAuth state is single-use', () => {
  const store = new BrowserAuthStore(() => 1000);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  store.consumeState(state);
  assert.throws(() => store.consumeState(state), AuthStoreError);
});

test('expired transaction and one-time code are rejected', () => {
  let now = 1000;
  const store = new BrowserAuthStore(() => now);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  const tx = store.consumeState(state);
  store.complete(tx.transactionId, {
    accessToken: 'temporary-access-token',
    tokenExpiresAt: now + 3_600_000,
    account: null,
  });
  const poll = store.poll(transactionId, verifier);
  now += 2 * 60 * 1000 + 1;
  assert.throws(() => store.exchange(poll.code, transactionId, verifier), AuthStoreError);
});

test('provider error is returned once and then invalidated', () => {
  const store = new BrowserAuthStore(() => 1000);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  const tx = store.consumeState(state);
  store.fail(tx.transactionId, 'access_denied');
  assert.deepEqual(store.poll(transactionId, verifier), { status: 'error', error: 'access_denied' });
  assert.throws(() => store.poll(transactionId, verifier), AuthStoreError);
});
