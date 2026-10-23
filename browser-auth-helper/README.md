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
- Google access tokens are held only in helper process memory long enough for the app exchange. They are not logged or written to disk.
- If Google unexpectedly returns a refresh token, this implementation deliberately ignores it and never persists it.
- The Android app then keeps the access token only in process memory.

The custom-scheme hop is **not** the Google OAuth redirect URI; Google always returns to the registered HTTPS helper endpoint. The custom scheme is only an app-return fallback carrying a proof-bound one-time Miyorare code. If an owner-controlled verified HTTPS Android App Link becomes available later, it can replace this final app-return hop without changing the token-exchange boundary.

## Required configuration

Set these as deployment secrets/environment variables; **never commit their values**:

- `GOOGLE_OAUTH_CLIENT_ID` — Google Cloud OAuth 2.0 Web client id.
- `GOOGLE_OAUTH_CLIENT_SECRET` — corresponding Web client secret.
- `PUBLIC_BASE_URL` — owner-controlled public HTTPS origin for this helper, with no trailing slash.
- `PORT` — optional; defaults to `8080` (platforms such as Railway normally provide it).

In Google Cloud Console, authorize exactly:

```text
${PUBLIC_BASE_URL}/v1/google/callback
```

Enable the Google Drive API for the OAuth project and configure the consent screen according to Google's current requirements.

## Deployment topology

The helper intentionally keeps pending OAuth transactions, one-time codes, and temporary Google grants **in process memory only**. Deploy this implementation as a **single active replica**. If horizontal scaling is later required, first replace the in-memory transaction store with a shared short-TTL store that preserves the same atomic single-use/replay semantics; do not run independent replicas behind round-robin routing because callback and exchange may otherwise land on different state owners.

Restarting the helper invalidates in-flight browser login transactions. That is safe: the user simply starts browser sign-in again, and no long-term Google credential has been persisted.

## Android configuration

After the helper has an approved stable HTTPS URL, set `BrowserDriveAuthConfig.BASE_URL` in the Android app to that exact origin. It is intentionally blank in source until infrastructure ownership and the OAuth redirect are approved; the app disables the browser-login action rather than guessing a domain.

The Android manifest must route only `miyorare://google-drive-auth` to the dedicated browser-auth callback activity. The callback accepts only the transaction id, one-time app code, or sanitized provider error; it must never accept a Google access token or Google authorization code.

## Run

Requires Node.js 20+ and no third-party runtime dependencies.

```bash
npm test
npm start
```

A health endpoint is available at `GET /healthz` and contains no credential data.

## Provider contract

The Google-facing portion follows Google's web-server OAuth flow: exact registered HTTPS redirect URI, state validation, server-side client secret, authorization-code exchange, and online access. Google's native-app documentation says custom URI schemes are not supported as the Google OAuth redirect on Android; this implementation does not use one for that purpose. Re-check Google's current OAuth documentation before changing endpoint or redirect behavior.
