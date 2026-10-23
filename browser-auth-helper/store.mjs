import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

const TX_TTL_MS = 10 * 60 * 1000;
const CODE_TTL_MS = 2 * 60 * 1000;

export class AuthStoreError extends Error {}

export class BrowserAuthStore {
  constructor(now = () => Date.now()) {
    this.now = now;
    this.transactions = new Map();
    this.states = new Map();
    this.grants = new Map();
    this.codes = new Map();
  }

  begin(transactionId, codeChallenge) {
    validateToken(transactionId, 'transaction_id', 32, 128);
    validateToken(codeChallenge, 'code_challenge', 43, 128);
    const expiresAt = this.now() + TX_TTL_MS;
    const state = randomUrlSafe(32);
    this.transactions.set(transactionId, { codeChallenge, expiresAt });
    this.states.set(state, { transactionId, expiresAt });
    return { state, expiresAt };
  }

  consumeState(state) {
    validateToken(state, 'state', 32, 128);
    const item = this.states.get(state);
    this.states.delete(state);
    if (!item || item.expiresAt <= this.now()) throw new AuthStoreError('invalid_state');
    const tx = this.transactions.get(item.transactionId);
    if (!tx || tx.expiresAt <= this.now()) throw new AuthStoreError('expired');
    return { transactionId: item.transactionId, codeChallenge: tx.codeChallenge };
  }

  complete(transactionId, grant) {
    const tx = this.requireTransaction(transactionId);
    this.grants.set(transactionId, {
      ...grant,
      codeChallenge: tx.codeChallenge,
      expiresAt: Math.min(grant.expiresAt ?? tx.expiresAt, tx.expiresAt),
    });
  }

  fail(transactionId, error) {
    const tx = this.requireTransaction(transactionId);
    this.grants.set(transactionId, {
      error,
      codeChallenge: tx.codeChallenge,
      expiresAt: tx.expiresAt,
    });
  }

  poll(transactionId, verifier) {
    const tx = this.requireTransaction(transactionId);
    verifyProof(verifier, tx.codeChallenge);
    const grant = this.grants.get(transactionId);
    if (!grant) return { status: 'pending' };
    if (grant.error) {
      this.deleteTransaction(transactionId);
      return { status: 'error', error: grant.error };
    }
    if (grant.expiresAt <= this.now()) {
      this.deleteTransaction(transactionId);
      throw new AuthStoreError('expired');
    }
    let codeEntry = [...this.codes.entries()].find(([, entry]) => entry.transactionId === transactionId);
    if (!codeEntry) {
      const code = randomUrlSafe(32);
      this.codes.set(code, {
        transactionId,
        expiresAt: Math.min(this.now() + CODE_TTL_MS, grant.expiresAt),
      });
      codeEntry = [code, this.codes.get(code)];
    }
    return { status: 'ready', code: codeEntry[0] };
  }

  exchange(code, transactionId, verifier) {
    validateToken(code, 'code', 32, 128);
    const tx = this.requireTransaction(transactionId);
    verifyProof(verifier, tx.codeChallenge);
    const entry = this.codes.get(code);
    this.codes.delete(code);
    if (!entry || entry.transactionId !== transactionId || entry.expiresAt <= this.now()) {
      throw new AuthStoreError('invalid_code');
    }
    const grant = this.grants.get(transactionId);
    if (!grant || grant.error || grant.expiresAt <= this.now()) {
      this.deleteTransaction(transactionId);
      throw new AuthStoreError('expired');
    }
    this.deleteTransaction(transactionId);
    return grant;
  }

  cleanup() {
    const now = this.now();
    for (const [key, value] of this.states) if (value.expiresAt <= now) this.states.delete(key);
    for (const [key, value] of this.codes) if (value.expiresAt <= now) this.codes.delete(key);
    for (const [key, value] of this.transactions) if (value.expiresAt <= now) this.deleteTransaction(key);
  }

  requireTransaction(transactionId) {
    validateToken(transactionId, 'transaction_id', 32, 128);
    const tx = this.transactions.get(transactionId);
    if (!tx || tx.expiresAt <= this.now()) {
      this.deleteTransaction(transactionId);
      throw new AuthStoreError('expired');
    }
    return tx;
  }

  deleteTransaction(transactionId) {
    this.transactions.delete(transactionId);
    this.grants.delete(transactionId);
    for (const [state, item] of this.states) if (item.transactionId === transactionId) this.states.delete(state);
    for (const [code, item] of this.codes) if (item.transactionId === transactionId) this.codes.delete(code);
  }
}

export function challengeForVerifier(verifier) {
  validateToken(verifier, 'code_verifier', 43, 256);
  return createHash('sha256').update(verifier).digest('base64url');
}

function verifyProof(verifier, expectedChallenge) {
  const actual = challengeForVerifier(verifier);
  const a = Buffer.from(actual);
  const b = Buffer.from(expectedChallenge);
  if (a.length !== b.length || !timingSafeEqual(a, b)) throw new AuthStoreError('proof_mismatch');
}

function validateToken(value, name, min, max) {
  if (typeof value !== 'string' || value.length < min || value.length > max || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new AuthStoreError(`invalid_${name}`);
  }
}

function randomUrlSafe(bytes) {
  return randomBytes(bytes).toString('base64url');
}
