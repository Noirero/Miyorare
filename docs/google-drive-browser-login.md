# Google Drive Sync authentication modes

Miyorare supports two Google Drive authentication models. Both reuse the same direct Drive REST sync engine and the same hidden `appDataFolder`; neither mode sends Miyorare sync payloads through the browser-auth helper.

## Google Play Services mode

**Continue with Google** uses the existing Google Play Services account flow.

- Intended for devices with Google Play Services.
- Keeps the existing persistent Google account/session behavior.
- Supports the existing automatic, sync-on-start, and periodic/background sync behavior.
- Drive access remains limited to `https://www.googleapis.com/auth/drive.appdata`.

## Temporary browser mode

**Continue in browser** is the GMS-free option exposed from Sync Settings.

- Opens the device's external browser; Miyorare does not embed Google sign-in in a WebView.
- Requests only the Drive `appDataFolder` scope.
- Uses a short-lived HTTPS authorization transaction plus a proof-bound one-time app exchange code.
- Keeps the resulting Google access token only in Miyorare process memory.
- Stores no browser refresh token and performs no silent browser-token renewal.
- Uses Google's returned access-token lifetime. Miyorare refuses to start a new sync when the token is already too close to expiry.
- If a later Drive request returns 401, the temporary token is invalidated and the user must sign in through the browser again.
- Process death ends the temporary credential. Reopening Miyorare does not restore browser Google access from preferences or a database.
- Browser mode is for foreground/manual sync. It does not qualify as a persistent credential for periodic WorkManager jobs or sync-on-start.

The account name/email/photo cached for display are metadata only. They are never treated as proof that a browser credential still exists.

## Browser return flow

Until an owner-controlled verified Android App Link is configured, Miyorare uses a proof-bound custom-scheme app return **after** Google's HTTPS web-server callback:

1. Miyorare creates a random transaction and process-local verifier and opens the helper's HTTPS start URL with only the transaction id and verifier challenge.
2. The helper redirects to Google with a random single-use OAuth `state`, `access_type=online`, the `drive.appdata` scope, and its exact registered HTTPS callback.
3. Google returns its authorization code or provider error to the helper over HTTPS. Google is never configured to redirect directly to Miyorare's custom scheme.
4. The helper validates `state`, exchanges the Google authorization code server-side, resolves minimal account identity, and retains the temporary Google grant only in helper process memory.
5. The helper creates a short-lived single-use **Miyorare app code** and redirects the browser to `miyorare://google-drive-auth` with only the transaction id plus that app code. A provider denial uses a sanitized error instead.
6. The dedicated Android callback activity validates the scheme/host and matches the transaction against the process-local pending login. Unrelated or stale callbacks are rejected.
7. Miyorare sends the app code, transaction id, and process-local verifier to the helper's HTTPS exchange endpoint.
8. Only after the verifier matches does the helper consume the app code and return the temporary Google access token, actual remaining lifetime, and account metadata over HTTPS.
9. Miyorare keeps that access token only in memory and then talks directly to the Google Drive REST API.

The app code in the custom-scheme URL is not a Google credential and cannot be exchanged without the verifier that stays in Miyorare process memory. A future verified HTTPS Android App Link may replace the final custom-scheme app-return hop without changing the server-side Google callback or one-time exchange boundary.

## Onboarding

The onboarding Google action remains the existing optional GMS path. Browser login is intentionally deferred to **Settings → Sync → Google Drive Sync**, so onboarding does not duplicate the temporary OAuth lifecycle. A device without GMS can complete onboarding without signing in and use browser login later from Sync Settings.

## Infrastructure dependency

Browser login is enabled only after the approved helper origin and Google OAuth Web client are configured. `BrowserDriveAuthConfig.BASE_URL` stays blank until that exact HTTPS origin is known; the UI disables the browser-login action rather than guessing a deployment URL.

The helper deployment owns the Google client secret. The Android APK must never contain it. See `browser-auth-helper/README.md` for the exact deployment and redirect contract.
