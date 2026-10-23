import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, loadConfig } from './server.mjs';
import { challengeForVerifier } from './store.mjs';

const transactionId = 't'.repeat(48);
const verifier = 'v'.repeat(64);
const challenge = challengeForVerifier(verifier);
const config = {
  clientId: 'test-client-id',
  clientSecret: 'server-only-secret',
  publicBaseUrl: 'https://auth.example.test',
  port: 0,
};

async function withServer(fn, fetchImpl = mockGoogleFetch) {
  const server = createServer(config, { fetchImpl });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;
  try {
    await fn(base);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

async function start(base, id = transactionId) {
  const response = await fetch(
    `${base}/v1/google/start?transaction_id=${id}&code_challenge=${encodeURIComponent(challenge)}`,
    { redirect: 'manual' },
  );
  assert.equal(response.status, 302);
  return new URL(response.headers.get('location'));
}

async function postJson(base, path, body) {
  return fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}

async function mockGoogleFetch(url, options = {}) {
  if (String(url) === 'https://oauth2.googleapis.com/token') {
    const body = options.body;
    assert.equal(body.get('client_id'), 'test-client-id');
    assert.equal(body.get('client_secret'), 'server-only-secret');
    assert.equal(body.get('grant_type'), 'authorization_code');
    assert.equal(body.get('redirect_uri'), 'https://auth.example.test/v1/google/callback');
    return new Response(JSON.stringify({
      access_token: 'temporary-google-token',
      expires_in: 3600,
      refresh_token: 'must-be-discarded',
    }), { status: 200, headers: { 'content-type': 'application/json' } });
  }
  if (String(url).startsWith('https://www.googleapis.com/drive/v3/about')) {
    assert.equal(options.headers.Authorization, 'Bearer temporary-google-token');
    return new Response(JSON.stringify({
      user: {
        displayName: 'Reader',
        emailAddress: 'reader@example.com',
        photoLink: 'https://example.test/avatar.png',
      },
    }), { status: 200, headers: { 'content-type': 'application/json' } });
  }
  throw new Error(`Unexpected provider request: ${url}`);
}

test('configuration requires an exact HTTPS origin and server-only OAuth values', () => {
  const credentials = {
    GOOGLE_OAUTH_CLIENT_ID: 'id',
    GOOGLE_OAUTH_CLIENT_SECRET: 'secret',
  };
  assert.throws(() => loadConfig({ ...credentials, PUBLIC_BASE_URL: 'http://auth.example.test' }));
  assert.throws(() => loadConfig({ ...credentials, PUBLIC_BASE_URL: 'https://auth.example.test/oauth' }));
  assert.throws(() => loadConfig({ ...credentials, PUBLIC_BASE_URL: 'https://auth.example.test/?query=1' }));
  assert.throws(() => loadConfig({ ...credentials, PUBLIC_BASE_URL: 'https://user:pass@auth.example.test/' }));
  const parsed = loadConfig({
    ...credentials,
    PUBLIC_BASE_URL: 'https://auth.example.test/',
  });
  assert.equal(parsed.publicBaseUrl, 'https://auth.example.test');
});

test('start requests only appDataFolder online access with exact HTTPS Google callback and state', async () => {
  await withServer(async (base) => {
    const auth = await start(base);
    assert.equal(auth.origin + auth.pathname, 'https://accounts.google.com/o/oauth2/v2/auth');
    assert.equal(auth.searchParams.get('client_id'), 'test-client-id');
    assert.equal(auth.searchParams.get('redirect_uri'), 'https://auth.example.test/v1/google/callback');
    assert.equal(auth.searchParams.get('response_type'), 'code');
    assert.equal(auth.searchParams.get('scope'), 'https://www.googleapis.com/auth/drive.appdata');
    assert.equal(auth.searchParams.get('access_type'), 'online');
    assert.ok(auth.searchParams.get('state'));
    assert.equal(auth.searchParams.has('code'), false);
    assert.equal(auth.searchParams.has('access_token'), false);
  });
});

test('Google callback returns only a one-time app code, then HTTPS exchange returns temporary token once', async () => {
  await withServer(async (base) => {
    const auth = await start(base);
    const state = auth.searchParams.get('state');
    const callback = await fetch(
      `${base}/v1/google/callback?state=${encodeURIComponent(state)}&code=google-authorization-code`,
      { redirect: 'manual' },
    );
    assert.equal(callback.status, 302);
    const appReturn = new URL(callback.headers.get('location'));
    assert.equal(appReturn.protocol, 'miyorare:');
    assert.equal(appReturn.host, 'google-drive-auth');
    assert.equal(appReturn.searchParams.get('transaction_id'), transactionId);
    const appCode = appReturn.searchParams.get('code');
    assert.ok(appCode);
    assert.equal(appReturn.searchParams.has('access_token'), false);
    assert.equal(appReturn.searchParams.has('refresh_token'), false);
    assert.equal(appReturn.toString().includes('google-authorization-code'), false);
    assert.equal(appReturn.toString().includes('temporary-google-token'), false);
    assert.equal(appReturn.toString().includes('must-be-discarded'), false);

    const wrongProof = await postJson(base, '/v1/google/exchange', {
      transaction_id: transactionId,
      code_verifier: 'x'.repeat(64),
      code: appCode,
    });
    assert.equal(wrongProof.status, 400);

    const exchange = await postJson(base, '/v1/google/exchange', {
      transaction_id: transactionId,
      code_verifier: verifier,
      code: appCode,
    });
    assert.equal(exchange.status, 200);
    const payload = await exchange.json();
    assert.equal(payload.access_token, 'temporary-google-token');
    assert.ok(payload.expires_in > 3500 && payload.expires_in <= 3600);
    assert.equal(payload.refresh_token, undefined);
    assert.equal(payload.account.email, 'reader@example.com');

    const replay = await postJson(base, '/v1/google/exchange', {
      transaction_id: transactionId,
      code_verifier: verifier,
      code: appCode,
    });
    assert.ok(replay.status === 400 || replay.status === 410);
  });
});

test('provider denial returns a sanitized app error and replayed state fails', async () => {
  await withServer(async (base) => {
    const auth = await start(base, 'a'.repeat(48));
    const state = auth.searchParams.get('state');
    const denial = await fetch(
      `${base}/v1/google/callback?state=${encodeURIComponent(state)}&error=access_denied`,
      { redirect: 'manual' },
    );
    assert.equal(denial.status, 302);
    const appReturn = new URL(denial.headers.get('location'));
    assert.equal(appReturn.protocol, 'miyorare:');
    assert.equal(appReturn.host, 'google-drive-auth');
    assert.equal(appReturn.searchParams.get('transaction_id'), 'a'.repeat(48));
    assert.equal(appReturn.searchParams.get('error'), 'access_denied');
    assert.equal(appReturn.searchParams.has('code'), false);

    const replayState = await fetch(
      `${base}/v1/google/callback?state=${encodeURIComponent(state)}&error=access_denied`,
      { redirect: 'manual' },
    );
    assert.equal(replayState.status, 410);
  });
});

test('missing provider code returns safe app error and malformed state never routes into the app', async () => {
  await withServer(async (base) => {
    const auth = await start(base, 'b'.repeat(48));
    const state = auth.searchParams.get('state');
    const missingCode = await fetch(
      `${base}/v1/google/callback?state=${encodeURIComponent(state)}`,
      { redirect: 'manual' },
    );
    assert.equal(missingCode.status, 302);
    const appReturn = new URL(missingCode.headers.get('location'));
    assert.equal(appReturn.searchParams.get('error'), 'provider_error');
    assert.equal(appReturn.searchParams.has('code'), false);

    const invalidState = await fetch(
      `${base}/v1/google/callback?state=${'z'.repeat(48)}&code=google-code`,
      { redirect: 'manual' },
    );
    assert.equal(invalidState.status, 410);
    assert.equal(invalidState.headers.has('location'), false);
  });
});

test('provider exchange failure returns sanitized app error without exposing provider response', async () => {
  const failingFetch = async (url) => {
    if (String(url) === 'https://oauth2.googleapis.com/token') {
      return new Response(JSON.stringify({ error: 'invalid_grant', secret_detail: 'do-not-leak' }), {
        status: 400,
        headers: { 'content-type': 'application/json' },
      });
    }
    throw new Error(`Unexpected provider request: ${url}`);
  };
  await withServer(async (base) => {
    const auth = await start(base, 'c'.repeat(48));
    const state = auth.searchParams.get('state');
    const callback = await fetch(
      `${base}/v1/google/callback?state=${encodeURIComponent(state)}&code=provider-code`,
      { redirect: 'manual' },
    );
    assert.equal(callback.status, 302);
    const appReturn = new URL(callback.headers.get('location'));
    assert.equal(appReturn.searchParams.get('error'), 'provider_error');
    assert.equal(appReturn.toString().includes('invalid_grant'), false);
    assert.equal(appReturn.toString().includes('do-not-leak'), false);
    assert.equal(appReturn.toString().includes('provider-code'), false);
  }, failingFetch);
});
