# Miyorare Google Drive browser-auth helper

This service exists only to complete **temporary Google Drive OAuth authorization** for Miyorare devices that do not use Google Play Services. It never receives or proxies favourites, history, categories, covers, snapshots, manga metadata, or any other sync payload. After authorization, Miyorare talks directly to the Google Drive REST API.

## Security model

- Google OAuth uses a **Web application** client and an exact HTTPS callback: `${PUBLIC_BASE_URL}/v1/google/callback`.
- The requested Drive scope is only `https://www.googleapis.com/auth/drive.appdata`.
- OAuth uses `access_type=online`; no offline/long-lived browser credential is requested.
- Google `state` is random, server-side, short-lived, and single-use.
- The Android app supplies a random transaction id and SHA-256 proof challenge. Its verifier never appears in the browser URL.
- After Google's HTTPS callback, the app polls the helper over HTTPS and must prove the verifier before it can obtain the short-lived **one-time app code**.
- The one-time app code is then exchanged over HTTPS exactly once. Replay, stale state, stale code, wrong transaction, and wrong verifier are rejected.
- Google access tokens are held only in helper process memory long enough for that exchange. They are not logged or written to disk.
- If Google unexpectedly returns a refresh token, this implementation deliberately ignores it and never persists it.
- The Android app then keeps the access token only in process memory.

The polling return path is the safe fallback while no owner-controlled verified Android App Link domain is configured. A future verified App Link may replace the user returning to the app manually without changing the token-exchange security boundary.

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

## Android configuration

After the helper has an approved stable HTTPS URL, set `BrowserDriveAuthConfig.BASE_URL` in the Android app to that exact origin. It is intentionally blank in source until infrastructure ownership and the OAuth redirect are approved; the app disables the browser-login action rather than guessing a domain.

## Run

Requires Node.js 20+ and no third-party runtime dependencies.

```bash
npm test
npm start
```

A health endpoint is available at `GET /healthz` and contains no credential data.

## Provider contract

The implementation follows Google's web-server OAuth flow: exact registered redirect URI, HTTPS, state validation, server-side client secret, authorization-code exchange, and online access. Re-check Google's current OAuth documentation before changing endpoint/redirect behavior.
