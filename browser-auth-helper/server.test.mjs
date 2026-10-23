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

test('configuration requires HTTPS and server-only OAuth values', () => {
  assert.throws(() => loadConfig({
    PUBLIC_BASE_URL: 'http://auth.example.test',
    GOOGLE_OAUTH_CLIENT_ID: 'id',
    GOOGLE_OAUTH_CLIENT_SECRET: 'secret',
  }));
  const parsed = loadConfig({
    PUBLIC_BASE_URL: 'https://auth.example.test/',
    GOOGLE_OAUTH_CLIENT_ID: 'id',
    GOOGLE_OAUTH_CLIENT_SECRET: 'secret',
  });
  assert.equal(parsed.publicBaseUrl, 'https://auth.example.test');
});

test('start requests only appDataFolder online access with exact HTTPS callback and state', async () => {
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

test('pending poll reveals nothing before provider callback', async () => {
  await withServer(async (base) => {
    await start(base);
    const response = await postJson(base, '/v1/google/poll', {
      transaction_id: transactionId,
      code_verifier: verifier,
    });
    assert.equal(response.status, 202);
    assert.deepEqual(await response.json(), { status: 'pending' });
  });
});

test('callback to poll to exchange returns temporary token once and never exposes refresh token', async () => {
  await withServer(async (base) => {
    const auth = await start(base);
    const state = auth.searchParams.get('state');
    const callback = await fetch(`${base}/v1/google/callback?state=${encodeURIComponent(state)}&code=google-code`);
    assert.equal(callback.status, 200);
    const callbackHtml = await callback.text();
    assert.equal(callbackHtml.includes('temporary-google-token'), false);
    assert.equal(callbackHtml.includes('must-be-discarded'), false);

    const poll = await postJson(base, '/v1/google/poll', {
      transaction_id: transactionId,
      code_verifier: verifier,
    });
    assert.equal(poll.status, 200);
    const { code } = await poll.json();
    assert.ok(code);

    const exchange = await postJson(base, '/v1/google/exchange', {
      transaction_id: transactionId,
      code_verifier: verifier,
      code,
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
      code,
    });
    assert.ok(replay.status === 400 || replay.status === 410);
  });
});

test('wrong verifier, replayed state, and malformed callback fail safely', async () => {
  await withServer(async (base) => {
    const firstAuth = await start(base, 'a'.repeat(48));
    const firstState = firstAuth.searchParams.get('state');
    const wrongProof = await postJson(base, '/v1/google/poll', {
      transaction_id: 'a'.repeat(48),
      code_verifier: 'x'.repeat(64),
    });
    assert.equal(wrongProof.status, 400);

    const denial = await fetch(`${base}/v1/google/callback?state=${encodeURIComponent(firstState)}&error=access_denied`);
    assert.equal(denial.status, 200);
    const replayState = await fetch(`${base}/v1/google/callback?state=${encodeURIComponent(firstState)}&error=access_denied`);
    assert.equal(replayState.status, 410);

    const secondAuth = await start(base, 'b'.repeat(48));
    const secondState = secondAuth.searchParams.get('state');
    const malformed = await fetch(`${base}/v1/google/callback?state=${encodeURIComponent(secondState)}`);
    assert.equal(malformed.status, 400);
    const poll = await postJson(base, '/v1/google/poll', {
      transaction_id: 'b'.repeat(48),
      code_verifier: verifier,
    });
    assert.equal(poll.status, 200);
    assert.deepEqual(await poll.json(), { error: 'provider_error' });
  });
});
