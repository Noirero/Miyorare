# Library Sync — Batch 2

Owner-request, non-theme; target `beta`; working branch `feature/library-sync-services`.

## Audit and API decisions (2026-10-02)

Fetched both remote branches before editing. Original head: `93f9a247f`; base at audit: `9fcf1d2a5`. The original seven commits add seven foundation files, not a working engine or service. Existing Google Drive sync, `SyncMerger`, repositories/authenticators/storage for Tracking, and the hub were inspected before changes.

| Service | Status | Official auth | Library pull | Library push | Rate limit / application throttle | Verification | Block reason |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MangaDex | BLOCKED | Public OAuth clients explicitly unavailable; personal password grant needs a personal client secret | API operations exist but inaccessible under the permitted mobile auth design | Same auth blocker | No requests issued by this integration | Official documentation reviewed | No documented available public/mobile client flow; no secret embedded in the APK |
| MangaUpdates | BLOCKED | `PUT /account/login`; bearer JWT security scheme | `/lists`, `POST /lists/{id}/search` | `/lists/series`, `/lists/series/update`; documented five-second update delay | Acceptable-use policy requires reasonable spacing; mutations can return HTTP 412 for five-second update delay | Downloaded current official OpenAPI; inspected login/response/context/list schemas | Login returns generic `ApiResponseV1.context`, and `ApiContextV1` only has `additionalProperties: true`. Token field/structure is not specified. No guessed `session_token`, `token` or `access_token` parsing |
| AniList | IMPLEMENTED — verification in progress | Documented implicit grant with auth-pin redirect; client ID supplied by user; access token validated using `Viewer`; no secret, no unsupported PKCE/refresh | GraphQL `Page.mediaList(userId, type: MANGA)` with 50-entry pages and `updatedAt` | `SaveMediaListEntry(mediaId, progress, status)` | Official current degraded limit 30/minute (normal 90/minute); application minimum interval 2.1 seconds; HTTP 429 cooldown persisted | Official auth, reference schemas and rate policy reviewed; no real-account login/pull/push yet | — |
| Kitsu | IMPLEMENTED — verification in progress | Documented OAuth password and refresh grants; password kept only in memory; encrypted token pair; one refresh/replay after HTTP 401 | JSON:API `/library-entries` filtered by user and manga; include manga; 20-entry pagination | Resolve existing entry then PATCH; POST with user/manga relationships only if absent | No numeric official limit established; application conservative interval 1.1 seconds and persisted `Retry-After` cooldown | Official blueprint/auth docs and existing KitsuRepository reviewed; no real-account login/pull/push yet | — |
| NovelUpdates | BLOCKED | No official library API auth contract found | No official Reading List API found | No official Reading List API found | No integration requests | Official Reading List page and official-domain searches reviewed | Reading List website/export is not a documented API. No scraping or private endpoint implementation |
| RanobeDB | BLOCKED | Public database reads; no user-list auth operation | Official docs say user lists are unavailable | API v0 is read-only | Official guidance: do not exceed 60 requests/minute; no integration requests | Official API v0 docs rechecked | Docs explicitly limit API to read-only database querying, with user lists a possible future feature |

### Official sources actually used

- MangaDex: https://api.mangadex.org/docs/02-authentication/
- MangaUpdates: https://api.mangaupdates.com/ and https://api.mangaupdates.com/openapi.yaml
- AniList authentication: https://docs.anilist.co/guide/auth/ and https://docs.anilist.co/guide/auth/implicit
- AniList rate policy: https://docs.anilist.co/guide/rate-limiting
- AniList schema references: https://docs.anilist.co/reference/object/page, https://docs.anilist.co/reference/object/medialist, https://docs.anilist.co/reference/mutation
- AniList official documentation source (also used where the docs host returned 403): https://github.com/AniList/ApiV2-GraphQL-Docs
- Kitsu auth/JSON:API: https://hummingbird-me.github.io/api-docs/ and https://kitsu.docs.apiary.io/
- Kitsu official detailed library blueprint: https://github.com/hummingbird-me/api-docs/blob/master/apiary.apib
- NovelUpdates: https://www.novelupdates.com/reading-list/
- RanobeDB: https://ranobedb.org/api/docs/v0

No real credentials were supplied. Unauthenticated AniList/Kitsu probes returned HTTP 403 in this environment; they do not establish live account functionality.

## Architecture and behavior

- Keep the initial `LibrarySyncService` contract and last-write-wins merge. Exact timestamp ties choose local, including ties where local progress is lower. AniList epoch seconds and Kitsu ISO timestamps become `Instant`; local database timestamps use epoch milliseconds. Unknown/invalid server timestamps fail visibly rather than inventing a last-write time.
- `LibrarySyncMappingStore` now uses Room in `MangaDatabase`. Service + external media ID is the primary identity; a unique index prevents two remote works from being linked to the same local manga for a service. A local manga can have different AniList and Kitsu external IDs. Local manga deletion cascades mapping deletion.
- **Migration 49 → 50** adds `library_sync_mappings`, indexes/foreign key, and `library_sync_entries`. Remote snapshots form a durable import inbox. Existing tables are unchanged. The unpublished initial standalone SQLite helper was never wired to a service/UI; no deployed records are assumed or silently reinterpreted.
- Tokens are AES-GCM encrypted with an Android Keystore key, random IVs and service/key names bound as authenticated additional data. Atomic ciphertext files live in `noBackupFilesDir`, not plaintext preferences and not preference/Drive backups. Malformed ciphertext, corruption or lost keystore keys require reauthentication. Access/refresh/user ID are stored atomically in one encrypted session. Credential objects do not generate a field-printing `toString`; passwords are never persisted or logged.
- Isolated service HTTP clients have no Tracking authenticators, plaintext storage, debug curl logger or redirect-following token leakage. Existing AniList/Kitsu repositories cannot be directly reused because their auth/storage and writes to Tracking entities are coupled. Safe reuse includes Kitsu's media-type constant, the existing remote-progress mapping helper, and optional read-only reuse of Tracking's global manga external IDs. The user still verifies the remote title before confirming the new Library Sync mapping. No Tracking source file is modified.
- One engine and one Hilt Worker class. Periodic work every six hours dispatches the same unique per-service one-time work used by manual sync. Network constraint, exponential backoff, bounded retry for IO/408/429/5xx, no auth/validation/TLS-certificate retry; persisted `Retry-After` cooldown; per-service monotonic throttling. Service mutexes also serialize login/logout/mapping edits with sync. Every 20 pushed entries is checkpointed with server timestamps to reduce replay after cancellation/retry.
- Pull and push default on after login. Turning pull off prevents local remote-data application/inbox insertion. Push still reads remote timestamps for LWW. Turning both off does no API work. Successful sync time advances only after enabled operations complete; errors are sanitized and surfaced. Cancellation is propagated.
- Background pull **never adds favourites**. The detail inbox asks the user to choose the precise cached source manga and destination category, then explicitly confirm the import/favourite. Uncached source manga must first be opened through existing Search/details. No fabricated source manga, title-based automatic matching, automatic source requests or private-library leakage.
- Push links are explicit and validated against the official catalog API. Only public local favourites with confirmed per-service mappings are pushed. Removed favourites are not resurrected automatically. Private-only content and incognito reading are excluded. Remote deletions do not delete local favourites.
- Remote progress is retained even if chapter snapshots are unavailable. Reader-history application uses the existing chapter mapping helper only for a single known branch; ambiguous branches or deleted history are not guessed or resurrected. Later local reading supersedes the saved remote snapshot when its timestamp is newer. Status/progress can be edited in the shared detail template.
- Logout clears encrypted credentials, service mappings and inbox, and cancels per-service work. Login resets account-specific mappings to avoid transferring an old account's data into a new account. Google Drive navigation remains the original `SyncSettingsFragment`; `SyncWorker` and `SyncMerger` are untouched.
- AniList auth-pin requires a registered client with redirect `https://anilist.co/api/v2/oauth/pin`. The UI opens the documented implicit flow using the entered public client ID, then validates the pasted token. It does not reuse Tracking's code-grant callback or embedded secret.

## Validation

In progress. Required commands: `:app:testDebugUnitTest`, `:app:assemblePreview`, `:app:assembleDebugAndroidTest`, `:app:lintPreview`. Android instrumentation requires an available device/emulator; real-account service verification remains separate.

No `.github/workflows/**` changes. PR must target `beta` and carry `ci:owner-request`.
