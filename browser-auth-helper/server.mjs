import http from 'node:http';
import { URL, URLSearchParams } from 'node:url';
import { BrowserAuthStore, AuthStoreError } from './store.mjs';

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
const AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';
const DRIVE_ABOUT_ENDPOINT = 'https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,photoLink)';
const APP_CALLBACK = 'miyorare://google-drive-auth';
const MAX_BODY_BYTES = 16 * 1024;

export function loadConfig(env = process.env) {
  const publicBaseUrl = new URL(required(env.PUBLIC_BASE_URL, 'PUBLIC_BASE_URL'));
  if (publicBaseUrl.protocol !== 'https:') throw new Error('PUBLIC_BASE_URL must use HTTPS');
  publicBaseUrl.pathname = publicBaseUrl.pathname.replace(/\/$/, '');
  return {
    clientId: required(env.GOOGLE_OAUTH_CLIENT_ID, 'GOOGLE_OAUTH_CLIENT_ID'),
    clientSecret: required(env.GOOGLE_OAUTH_CLIENT_SECRET, 'GOOGLE_OAUTH_CLIENT_SECRET'),
    publicBaseUrl: publicBaseUrl.toString().replace(/\/$/, ''),
    port: Number(env.PORT || 8080),
  };
}

export function createServer(config, { fetchImpl = fetch, store = new BrowserAuthStore() } = {}) {
  const redirectUri = `${config.publicBaseUrl}/v1/google/callback`;
  const server = http.createServer(async (req, res) => {
    setSecurityHeaders(res);
    try {
      const url = new URL(req.url, config.publicBaseUrl);
      if (req.method === 'GET' && url.pathname === '/healthz') return json(res, 200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/v1/google/start') {
        const transactionId = url.searchParams.get('transaction_id');
        const codeChallenge = url.searchParams.get('code_challenge');
        const { state } = store.begin(transactionId, codeChallenge);
        const auth = new URL(AUTH_ENDPOINT);
        auth.search = new URLSearchParams({
          client_id: config.clientId,
          redirect_uri: redirectUri,
          response_type: 'code',
          scope: DRIVE_SCOPE,
          access_type: 'online',
          state,
        }).toString();
        res.writeHead(302, { Location: auth.toString() });
        return res.end();
      }
      if (req.method === 'GET' && url.pathname === '/v1/google/callback') {
        const state = url.searchParams.get('state');
        const tx = store.consumeState(state);
        const providerError = url.searchParams.get('error');
        if (providerError) {
          store.deleteTransaction(tx.transactionId);
          return redirectToApp(res, tx.transactionId, { error: sanitizeProviderError(providerError) });
        }
        const authCode = url.searchParams.get('code');
        if (!authCode) {
          store.deleteTransaction(tx.transactionId);
          return redirectToApp(res, tx.transactionId, { error: 'provider_error' });
        }
        try {
          const token = await exchangeGoogleCode(fetchImpl, config, redirectUri, authCode);
          const account = await fetchDriveAccount(fetchImpl, token.accessToken);
          store.complete(tx.transactionId, {
            accessToken: token.accessToken,
            tokenExpiresAt: Date.now() + token.expiresIn * 1000,
            account,
          });
          const appCode = store.issueCode(tx.transactionId);
          return redirectToApp(res, tx.transactionId, { code: appCode });
        } catch (error) {
          store.deleteTransaction(tx.transactionId);
          console.error('browser-auth provider exchange failed', error?.name || 'Error');
          return redirectToApp(res, tx.transactionId, { error: 'provider_error' });
        }
      }
      if (req.method === 'POST' && url.pathname === '/v1/google/exchange') {
        const body = await readJson(req);
        const grant = store.exchange(body.code, body.transaction_id, body.code_verifier);
        const expiresIn = Math.max(0, Math.floor((grant.tokenExpiresAt - Date.now()) / 1000));
        if (expiresIn <= 0) throw new AuthStoreError('expired');
        return json(res, 200, {
          access_token: grant.accessToken,
          expires_in: expiresIn,
          account: grant.account ?? null,
        });
      }
      return json(res, 404, { error: 'not_found' });
    } catch (error) {
      if (error instanceof AuthStoreError) return json(res, mapStoreStatus(error.message), { error: error.message });
      // Never echo OAuth responses, authorization codes, credentials, or access tokens.
      console.error('browser-auth request failed', error?.name || 'Error');
      return json(res, 502, { error: 'oauth_service_error' });
    }
  });
  const cleanup = setInterval(() => store.cleanup(), 60_000);
  cleanup.unref();
  server.on('close', () => clearInterval(cleanup));
  return server;
}

async function exchangeGoogleCode(fetchImpl, config, redirectUri, code) {
  const response = await fetchImpl(TOKEN_ENDPOINT, {
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
  // Deliberately read only access_token/expires_in. Any refresh_token returned by Google is discarded.
  const accessToken = typeof payload.access_token === 'string' ? payload.access_token : '';
  const expiresIn = Number(payload.expires_in);
  if (!accessToken || !Number.isFinite(expiresIn) || expiresIn <= 0) throw new Error('invalid_google_token_response');
  return { accessToken, expiresIn };
}

async function fetchDriveAccount(fetchImpl, accessToken) {
  const response = await fetchImpl(DRIVE_ABOUT_ENDPOINT, {
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

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new AuthStoreError('request_too_large');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new AuthStoreError('malformed_json');
  }
}

function redirectToApp(res, transactionId, result) {
  const callback = new URL(APP_CALLBACK);
  callback.searchParams.set('transaction_id', transactionId);
  if (result.code) callback.searchParams.set('code', result.code);
  if (result.error) callback.searchParams.set('error', result.error);
  res.writeHead(302, { Location: callback.toString() });
  res.end();
}

function setSecurityHeaders(res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Content-Security-Policy', "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
}

function json(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(payload));
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

if (import.meta.url === `file://${process.argv[1]}`) {
  const config = loadConfig();
  createServer(config).listen(config.port, '0.0.0.0', () => {
    console.log(`Miyorare browser auth helper listening on ${config.port}`);
  });
}
