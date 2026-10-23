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

test('one-time app code is only issued after the provider grant exists', () => {
  const store = new BrowserAuthStore(() => 1000);
  store.begin(transactionId, challengeForVerifier(verifier));
  assert.throws(() => store.issueCode(transactionId), AuthStoreError);
});

test('wrong proof cannot exchange a transaction', () => {
  const { store } = readyStore();
  const code = store.issueCode(transactionId);
  assert.throws(() => store.exchange(code, transactionId, 'x'.repeat(64)), AuthStoreError);
  const grant = store.exchange(code, transactionId, verifier);
  assert.equal(grant.accessToken, 'temporary-access-token');
});

test('one-time app code is single-use', () => {
  const { store } = readyStore();
  const code = store.issueCode(transactionId);
  const grant = store.exchange(code, transactionId, verifier);
  assert.equal(grant.accessToken, 'temporary-access-token');
  assert.throws(() => store.exchange(code, transactionId, verifier), AuthStoreError);
});

test('OAuth state is single-use', () => {
  const store = new BrowserAuthStore(() => 1000);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  store.consumeState(state);
  assert.throws(() => store.consumeState(state), AuthStoreError);
});

test('expired one-time code is rejected', () => {
  let now = 1000;
  const store = new BrowserAuthStore(() => now);
  const { state } = store.begin(transactionId, challengeForVerifier(verifier));
  const tx = store.consumeState(state);
  store.complete(tx.transactionId, {
    accessToken: 'temporary-access-token',
    tokenExpiresAt: now + 3_600_000,
    account: null,
  });
  const code = store.issueCode(transactionId);
  now += 2 * 60 * 1000 + 1;
  assert.throws(() => store.exchange(code, transactionId, verifier), AuthStoreError);
});

test('deleting a transaction invalidates its app code and grant', () => {
  const { store } = readyStore();
  const code = store.issueCode(transactionId);
  store.deleteTransaction(transactionId);
  assert.throws(() => store.exchange(code, transactionId, verifier), AuthStoreError);
});
