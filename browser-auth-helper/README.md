# Miyorare Google Drive browser-auth helper

This service exists only to complete **temporary Google Drive OAuth authorization** for Miyorare devices that do not use Google Play Services. It never receives or proxies favourites, history, categories, covers, snapshots, manga metadata, or any other sync payload. After authorization, Miyorare talks directly to the Google Drive REST API.

## Security model

- Google OAuth uses a **Web application** client and an exact HTTPS callback: `${PUBLIC_BASE_URL}/v1/google/callback`.
- The requested Drive scope is only `https://www.googleapis.com/auth/drive.appdata`.
- OAuth uses `access_type=online`; no offline/long-lived browser credential is requested.
- Google `state` is random, server-side, short-lived, and single-use.
- The Android app supplies a random transaction id and SHA-256 proof challenge. Its verifier never appears in the browser URL or app callback URL.
- Google returns only to the helper's registered HTTPS callback. The helper exchanges Google's authorization code server-side and never redirects that Google authorization code or Google access token into Android.
- After a successful provider exchange, the helper creates a short-lived **one-time app code** and redirects the browser to `miyorare://google-drive-auth` with only that app code and transaction id.
- The one-time app code is not a Google credential. Miyorare must still prove possession of the process-local verifier when it exchanges the app code over HTTPS.
- The app code is consumed exactly once. Replay, stale state, stale code, wrong transaction, and wrong verifier are rejected.
- If Google unexpectedly returns a refresh token, the implementation ignores it and never persists it.
- The Android app keeps the resulting access token only in process memory.

The custom-scheme hop is **not** the Google OAuth redirect URI. Google always returns to the registered HTTPS helper endpoint.

## Cloudflare Workers deployment

Cloudflare Workers is the preferred low-cost deployment for this helper. `worker.mjs` implements the same OAuth protocol as the Node helper, while a SQLite-backed Durable Object stores only short-lived pending transactions, one-time codes, and temporary access grants. This avoids relying on one long-running server process and keeps replay/TTL state coherent across Worker invocations.

The Durable Object is **not** Miyorare sync storage. No library, history, favourites, covers, snapshots, or Drive payloads pass through it.

The Worker configuration is in `wrangler.jsonc` and intentionally uses the Worker name:

```text
miyorare-drive-auth
```

When connecting the GitHub repository in Cloudflare, use:

```text
Repository: Noirero/Miyorare
Production branch: Amain2
Root directory: browser-auth-helper
Deploy command: npx wrangler deploy
```

The Worker name in the Cloudflare dashboard must match `miyorare-drive-auth` from `wrangler.jsonc`.

After the first deployment, Cloudflare provides a stable HTTPS `workers.dev` origin. Use that exact origin as `PUBLIC_BASE_URL`.

## Required runtime variables / secrets

Configure these in the Worker runtime settings; **never commit their values**:

- `PUBLIC_BASE_URL` — exact public Worker HTTPS origin, for example `https://miyorare-drive-auth.<account-subdomain>.workers.dev`.
- `GOOGLE_OAUTH_CLIENT_ID` — Google Cloud OAuth 2.0 **Web application** client id.
- `GOOGLE_OAUTH_CLIENT_SECRET` — corresponding Web client secret; store as a Cloudflare secret.

The legacy Node server also accepts `PORT`, defaulting to `8080`, but Cloudflare Workers does not use `PORT`.

In Google Cloud Console, authorize exactly:

```text
${PUBLIC_BASE_URL}/v1/google/callback
```

Enable the Google Drive API for the OAuth project and configure the OAuth consent screen according to Google's current requirements.

## Android configuration

Only after the Worker has a stable production HTTPS origin and Google OAuth is configured, set `BrowserDriveAuthConfig.BASE_URL` in the Android app to that exact origin. It remains blank until then so an APK cannot silently point at an unowned or incomplete OAuth deployment.

The Android manifest routes only `miyorare://google-drive-auth` to the dedicated browser-auth callback activity. The callback accepts only the transaction id, one-time app code, or sanitized provider error; it never accepts a Google access token or Google authorization code.

## Local / regression validation

Node.js 20+:

```bash
npm test
```

Validate Cloudflare bundling when dependencies are installed:

```bash
npm install
npm run check:cloudflare
```

The original Node implementation remains available for protocol regression tests and local server testing:

```bash
npm start
```

A health endpoint is available at `GET /healthz`. On Cloudflare it returns whether required OAuth runtime configuration is present without exposing credential values.

## Provider contract

The Google-facing portion follows Google's web-server OAuth flow: exact registered HTTPS redirect URI, state validation, server-side client secret, authorization-code exchange, and online access. The custom Android URI scheme is only the post-exchange app-return hop and is never registered as Google's OAuth redirect URI.
