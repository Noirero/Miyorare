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

Until an owner-controlled verified Android App Link domain is configured, Miyorare uses a safe HTTPS completion fallback:

1. Miyorare creates a random transaction and process-local verifier and opens the helper's HTTPS start URL.
2. The helper redirects to Google with a random single-use OAuth `state` and an exact registered HTTPS callback.
3. Google returns to the helper over HTTPS. The helper exchanges Google's authorization code server-side.
4. The helper page tells the user to return to Miyorare. No Google token or one-time app code is placed in that page URL.
5. Miyorare polls the helper over HTTPS and proves possession of the process-local verifier.
6. The helper reveals a short-lived one-time app code only after proof succeeds.
7. Miyorare exchanges that code once over HTTPS for the temporary access token and its actual remaining lifetime.
8. Miyorare then talks directly to the Google Drive REST API.

A future verified HTTPS Android App Link may make the return automatic without changing the credential boundary. Do not replace this with an unverified custom-scheme token callback.

## Onboarding

The onboarding Google action remains the existing optional GMS path. Browser login is intentionally deferred to **Settings → Sync → Google Drive Sync**, so onboarding does not duplicate the temporary OAuth lifecycle. A device without GMS can complete onboarding without signing in and use browser login later from Sync Settings.

## Infrastructure dependency

Browser login is enabled only after the approved helper origin and Google OAuth Web client are configured. `BrowserDriveAuthConfig.BASE_URL` stays blank until that exact HTTPS origin is known; the UI disables the browser-login action rather than guessing a deployment URL.

The helper deployment owns the Google client secret. The Android APK must never contain it. See `browser-auth-helper/README.md` for the exact deployment and redirect contract.
