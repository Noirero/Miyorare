import { DurableObject } from 'cloudflare:workers';

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
const AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';
const DRIVE_ABOUT_ENDPOINT = 'https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,photoLink)';
const APP_CALLBACK = 'miyorare://google-drive-auth';
const TX_TTL_MS = 10 * 60 * 1000;
const CODE_TTL_MS = 2 * 60 * 1000;
const MAX_BODY_BYTES = 16 * 1024;
const STORE_NAME = 'miyorare-browser-auth';

class AuthStoreError extends Error {}

export class OAuthStore extends DurableObject {
  async begin(transactionId, codeChallenge) {
    return this.#run(async () => {
      validateToken(transactionId, 'transaction_id', 32, 128);
      validateToken(codeChallenge, 'code_challenge', 43, 128);
      await this.#deleteTransaction(transactionId);
      const expiresAt = Date.now() + TX_TTL_MS;
      const state = randomUrlSafe(32);
      await this.ctx.storage.put(`tx:${transactionId}`, {
        codeChallenge,
        expiresAt,
        state,
        code: null,
      });
      await this.ctx.storage.put(`state:${state}`, { transactionId, expiresAt });
      await this.#scheduleAlarm(expiresAt);
      return { state, expiresAt };
    });
  }

  async consumeState(state) {
    return this.#run(async () => {
      validateToken(state, 'state', 32, 128);
      const stateKey = `state:${state}`;
      const item = await this.ctx.storage.get(stateKey);
      await this.ctx.storage.delete(stateKey);
      if (!item || item.expiresAt <= Date.now()) {
        if (item?.transactionId) await this.#deleteTransaction(item.transactionId);
        throw new AuthStoreError('invalid_state');
      }
      const tx = await this.#requireTransaction(item.transactionId);
      if (tx.state !== state) throw new AuthStoreError('invalid_state');
      tx.state = null;
      await this.ctx.storage.put(`tx:${item.transactionId}`, tx);
      return { transactionId: item.transactionId, codeChallenge: tx.codeChallenge };
    });
  }

  async complete(transactionId, grant) {
    return this.#run(async () => {
      const tx = await this.#requireTransaction(transactionId);
      if (!grant?.accessToken || !Number.isFinite(grant.tokenExpiresAt)) {
        throw new AuthStoreError('invalid_grant');
      }
      const storeExpiresAt = Math.min(grant.tokenExpiresAt, tx.expiresAt);
      await this.ctx.storage.put(`grant:${transactionId}`, {
        accessToken: grant.accessToken,
        tokenExpiresAt: grant.tokenExpiresAt,
        account: grant.account ?? null,
        storeExpiresAt,
      });
      await this.#scheduleAlarm(storeExpiresAt);
      return null;
    });
  }

  async issueCode(transactionId) {
    return this.#run(async () => {
      const tx = await this.#requireTransaction(transactionId);
      const grant = await this.ctx.storage.get(`grant:${transactionId}`);
      if (!grant || grant.storeExpiresAt <= Date.now()) {
        await this.#deleteTransaction(transactionId);
        throw new AuthStoreError('expired');
      }
      if (tx.code) await this.ctx.storage.delete(`code:${tx.code}`);
      const code = randomUrlSafe(32);
      const expiresAt = Math.min(Date.now() + CODE_TTL_MS, grant.storeExpiresAt, tx.expiresAt);
      tx.code = code;
      await this.ctx.storage.put(`tx:${transactionId}`, tx);
      await this.ctx.storage.put(`code:${code}`, { transactionId, expiresAt });
      await this.#scheduleAlarm(expiresAt);
      return code;
    });
  }

  async exchange(code, transactionId, verifier) {
    return this.#run(async () => {
      validateToken(code, 'code', 32, 128);
      const tx = await this.#requireTransaction(transactionId);
      await verifyProof(verifier, tx.codeChallenge);
      const codeKey = `code:${code}`;
      const entry = await this.ctx.storage.get(codeKey);
      await this.ctx.storage.delete(codeKey);
      if (!entry || entry.transactionId !== transactionId || entry.expiresAt <= Date.now()) {
        throw new AuthStoreError('invalid_code');
      }
      const grant = await this.ctx.storage.get(`grant:${transactionId}`);
      if (!grant || grant.storeExpiresAt <= Date.now()) {
        await this.#deleteTransaction(transactionId);
        throw new AuthStoreError('expired');
      }
      await this.#deleteTransaction(transactionId);
      return grant;
    });
  }

  async discard(transactionId) {
    return this.#run(async () => {
      validateToken(transactionId, 'transaction_id', 32, 128);
      await this.#deleteTransaction(transactionId);
      return null;
    });
  }

  async alarm() {
    const now = Date.now();
    let nextExpiry = Number.POSITIVE_INFINITY;

    const transactions = await this.ctx.storage.list({ prefix: 'tx:' });
    for (const [key, tx] of transactions) {
      if (!tx || tx.expiresAt <= now) {
        await this.#deleteTransaction(key.slice(3));
      } else {
        nextExpiry = Math.min(nextExpiry, tx.expiresAt);
      }
    }

    const states = await this.ctx.storage.list({ prefix: 'state:' });
    for (const [key, state] of states) {
      if (!state || state.expiresAt <= now) await this.ctx.storage.delete(key);
      else nextExpiry = Math.min(nextExpiry, state.expiresAt);
    }

    const codes = await this.ctx.storage.list({ prefix: 'code:' });
    for (const [key, code] of codes) {
      if (!code || code.expiresAt <= now) await this.ctx.storage.delete(key);
      else nextExpiry = Math.min(nextExpiry, code.expiresAt);
    }

    const grants = await this.ctx.storage.list({ prefix: 'grant:' });
    for (const [key, grant] of grants) {
      if (!grant || grant.storeExpiresAt <= now) await this.ctx.storage.delete(key);
      else nextExpiry = Math.min(nextExpiry, grant.storeExpiresAt);
    }

    if (Number.isFinite(nextExpiry)) await this.ctx.storage.setAlarm(nextExpiry);
    else await this.ctx.storage.deleteAlarm();
  }

  async #run(block) {
    try {
      return { ok: true, value: await block() };
    } catch (error) {
      if (error instanceof AuthStoreError) return { ok: false, error: error.message };
      throw error;
    }
  }

  async #requireTransaction(transactionId) {
    validateToken(transactionId, 'transaction_id', 32, 128);
    const tx = await this.ctx.storage.get(`tx:${transactionId}`);
    if (!tx || tx.expiresAt <= Date.now()) {
      await this.#deleteTransaction(transactionId);
      throw new AuthStoreError('expired');
    }
    return tx;
  }

  async #deleteTransaction(transactionId) {
    const txKey = `tx:${transactionId}`;
    const tx = await this.ctx.storage.get(txKey);
    const keys = [txKey, `grant:${transactionId}`];
    if (tx?.state) keys.push(`state:${tx.state}`);
    if (tx?.code) keys.push(`code:${tx.code}`);
    await Promise.all(keys.map((key) => this.ctx.storage.delete(key)));
  }

  async #scheduleAlarm(expiresAt) {
    const current = await this.ctx.storage.getAlarm();
    if (current === null || expiresAt < current) await this.ctx.storage.setAlarm(expiresAt);
  }
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (request.method === 'GET' && url.pathname === '/healthz') {
        return secureJson(200, { ok: true, configured: isConfigured(env) });
      }

      const config = loadConfig(env);
      if (url.origin !== config.publicBaseUrl) return secureJson(400, { error: 'invalid_origin' });
      const store = env.AUTH_STORE.getByName(STORE_NAME);
      const redirectUri = `${config.publicBaseUrl}/v1/google/callback`;

      if (request.method === 'GET' && url.pathname === '/v1/google/start') {
        const transactionId = url.searchParams.get('transaction_id');
        const codeChallenge = url.searchParams.get('code_challenge');
        const { state } = unwrapStore(await store.begin(transactionId, codeChallenge));
        const auth = new URL(AUTH_ENDPOINT);
        auth.search = new URLSearchParams({
          client_id: config.clientId,
          redirect_uri: redirectUri,
          response_type: 'code',
          scope: DRIVE_SCOPE,
          access_type: 'online',
          state,
        }).toString();
        return secureRedirect(auth.toString());
      }

      if (request.method === 'GET' && url.pathname === '/v1/google/callback') {
        const state = url.searchParams.get('state');
        const tx = unwrapStore(await store.consumeState(state));
        const providerError = url.searchParams.get('error');
        if (providerError) {
          unwrapStore(await store.discard(tx.transactionId));
          return redirectToApp(tx.transactionId, { error: sanitizeProviderError(providerError) });
        }
        const authCode = url.searchParams.get('code');
        if (!authCode) {
          unwrapStore(await store.discard(tx.transactionId));
          return redirectToApp(tx.transactionId, { error: 'provider_error' });
        }
        try {
          const token = await exchangeGoogleCode(config, redirectUri, authCode);
          const account = await fetchDriveAccount(token.accessToken);
          unwrapStore(await store.complete(tx.transactionId, {
            accessToken: token.accessToken,
            tokenExpiresAt: Date.now() + token.expiresIn * 1000,
            account,
          }));
          const appCode = unwrapStore(await store.issueCode(tx.transactionId));
          return redirectToApp(tx.transactionId, { code: appCode });
        } catch (error) {
          unwrapStore(await store.discard(tx.transactionId));
          console.error('browser-auth provider exchange failed', error?.name || 'Error');
          return redirectToApp(tx.transactionId, { error: 'provider_error' });
        }
      }

      if (request.method === 'POST' && url.pathname === '/v1/google/exchange') {
        const body = await readJson(request);
        const grant = unwrapStore(await store.exchange(body.code, body.transaction_id, body.code_verifier));
        const expiresIn = Math.max(0, Math.floor((grant.tokenExpiresAt - Date.now()) / 1000));
        if (expiresIn <= 0) throw new AuthStoreError('expired');
        return secureJson(200, {
          access_token: grant.accessToken,
          expires_in: expiresIn,
          account: grant.account ?? null,
        });
      }

      return secureJson(404, { error: 'not_found' });
    } catch (error) {
      if (error instanceof AuthStoreError) return secureJson(mapStoreStatus(error.message), { error: error.message });
      console.error('browser-auth request failed', error?.name || 'Error');
      return secureJson(502, { error: 'oauth_service_error' });
    }
  },
};

function isConfigured(env) {
  try {
    loadConfig(env);
    return true;
  } catch {
    return false;
  }
}

function loadConfig(env) {
  const publicBaseUrl = new URL(required(env.PUBLIC_BASE_URL, 'PUBLIC_BASE_URL'));
  if (publicBaseUrl.protocol !== 'https:') throw new Error('PUBLIC_BASE_URL must use HTTPS');
  if (
    publicBaseUrl.username ||
    publicBaseUrl.password ||
    publicBaseUrl.search ||
    publicBaseUrl.hash ||
    publicBaseUrl.pathname !== '/'
  ) {
    throw new Error('PUBLIC_BASE_URL must be an HTTPS origin without credentials, path, query, or fragment');
  }
  return {
    clientId: required(env.GOOGLE_OAUTH_CLIENT_ID, 'GOOGLE_OAUTH_CLIENT_ID'),
    clientSecret: required(env.GOOGLE_OAUTH_CLIENT_SECRET, 'GOOGLE_OAUTH_CLIENT_SECRET'),
    publicBaseUrl: publicBaseUrl.origin,
  };
}

async function exchangeGoogleCode(config, redirectUri, code) {
  const response = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
    body: new URLSearchParams({
      client_id: config.clientId,
      client_secret: config.clientSecret,
      code,
      grant_type: 'authorization_code',
      redirect_uri: redirectUri,
    }),
  });
  if (!response.ok) throw new Error('google_token_exchange_failed');
  const payload = await response.json();
  const accessToken = typeof payload.access_token === 'string' ? payload.access_token : '';
  const expiresIn = Number(payload.expires_in);
  if (!accessToken || !Number.isFinite(expiresIn) || expiresIn <= 0) throw new Error('invalid_google_token_response');
  return { accessToken, expiresIn };
}

async function fetchDriveAccount(accessToken) {
  const response = await fetch(DRIVE_ABOUT_ENDPOINT, {
    headers: { Authorization: `Bearer ${accessToken}`, accept: 'application/json' },
  });
  if (!response.ok) throw new Error('drive_account_lookup_failed');
  const payload = await response.json();
  const user = payload?.user ?? {};
  return {
    email: typeof user.emailAddress === 'string' ? user.emailAddress : null,
    display_name: typeof user.displayName === 'string' ? user.displayName : null,
    photo_url: typeof user.photoLink === 'string' ? user.photoLink : null,
  };
}

async function readJson(request) {
  const declaredLength = Number(request.headers.get('content-length') || 0);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) throw new AuthStoreError('request_too_large');
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) throw new AuthStoreError('request_too_large');
  try {
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not_object');
    return parsed;
  } catch {
    throw new AuthStoreError('malformed_json');
  }
}

function redirectToApp(transactionId, result) {
  const callback = new URL(APP_CALLBACK);
  callback.searchParams.set('transaction_id', transactionId);
  if (result.code) callback.searchParams.set('code', result.code);
  if (result.error) callback.searchParams.set('error', result.error);
  return secureRedirect(callback.toString());
}

function secureRedirect(location) {
  return withSecurityHeaders(new Response(null, {
    status: 302,
    headers: { Location: location },
  }));
}

function secureJson(status, payload) {
  return withSecurityHeaders(new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  }));
}

function withSecurityHeaders(response) {
  const headers = new Headers(response.headers);
  headers.set('Cache-Control', 'no-store');
  headers.set('Pragma', 'no-cache');
  headers.set('Referrer-Policy', 'no-referrer');
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Content-Security-Policy', "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function unwrapStore(result) {
  if (!result?.ok) throw new AuthStoreError(result?.error || 'store_error');
  return result.value;
}

function mapStoreStatus(error) {
  return ['expired', 'invalid_state', 'invalid_code'].includes(error) ? 410 : 400;
}

function sanitizeProviderError(error) {
  return ['access_denied', 'temporarily_unavailable'].includes(error) ? error : 'provider_error';
}

function required(value, name) {
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function verifyProof(verifier, expectedChallenge) {
  const actual = await challengeForVerifier(verifier);
  if (!constantTimeEqual(actual, expectedChallenge)) throw new AuthStoreError('proof_mismatch');
}

async function challengeForVerifier(verifier) {
  validateToken(verifier, 'code_verifier', 43, 256);
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function validateToken(value, name, min, max) {
  if (typeof value !== 'string' || value.length < min || value.length > max || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new AuthStoreError(`invalid_${name}`);
  }
}

function randomUrlSafe(bytes) {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return base64Url(data);
}

function base64Url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/g, '');
}

function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}
