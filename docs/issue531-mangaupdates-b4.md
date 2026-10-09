# MangaUpdates tracker

The service is wired into the existing Scrobbler repository map, DI set, tracker selector, account settings, Details and progress synchronization. It uses the current official v1 API at `https://api.mangaupdates.com/v1/`, with public series/search/author reads and authenticated account/list/rating operations. It creates no second tracker framework.

## Account lifecycle and security

The native sign-in form sends username/password directly to PUT `/account/login`, accepts known `session_token` response locations, and verifies the bearer session through GET `/account/profile` before saving it. No password is persisted, passed in an Intent, restored on rotation or logged. The form is not exported and remains screenshot-protected through the existing sensitive-content policy.

Only token/profile data are saved in Android Keystore AES-GCM ciphertext under `noBackupFilesDir`. Credentials are excluded from application and Android backups. Login, replacement, corruption, logout and authenticated 401 advance the account generation; late responses cannot clear a replacement session or publish stale private data. Logout clears locally immediately, then attempts provider revocation without delaying local logout.

The dedicated credential client uses normal TLS verification, the existing proxy/DNS settings, no curl logger, no cookies, no cache, no redirects and bounded timeouts/response size. The bearer interceptor checks exact HTTPS origin, port and API path and removes unsolicited authorization. Public reads have no bearer. Errors contain generic descriptions or HTTP codes, never response/login bodies.

## Identity, association and remote state

Catalog search returns genuine provider results with full `Long` series IDs, real names, covers and links. Its bounded page cursor does not permanently bind title matches. Explicit selector association first adopts an existing remote entry, including custom list, status, chapter/volume and rating; a missing entry is created only after the default wishlist is discovered and absence is rechecked.

The existing compound Room key is `(service, rate id, local manga id)`. MangaUpdates has no independent rate ID: its provider-local rate key is zero, while `target_id` retains the complete provider `Long`. No provider ID is truncated to `Int`, and no migration is needed. Backup pagination already includes all three key columns; a fixture spans more than one page with large series IDs and repeated zero rate keys.

List IDs are discovered by verified list types, not guessed numeric constants: read, wish, complete, hold and unfinished map to Reading, Planned, Completed, On hold and Dropped. Unsupported re-reading is hidden. Custom lists remain intact unless the user explicitly changes status. Chapter writes use non-regressing absolute progress and preserve volume/priority. Explicit volume edits update only volume and keep it remote/ephemeral; there is no new local volume column.

Rating reads preserve the documented numeric field on the provider's ten-point scale. Explicit normalized edits write an integer 1–10; zero explicitly deletes a rating. Unchanged ratings are not rewritten. Unsupported comments/start dates are not invented. Latest chapter is not treated as a verified total chapter count.

## Writes and synchronization

The provider owns one bounded coalescing reader-progress queue and a five-second list-write gate. Other trackers and Reader/History remain independent. Pending entries capture local manga, full tracker target and account generation. Mapping changes, privacy/incognito and account changes reject stale work; failures are visible in Details with explicit retry.

Each retry re-reads current remote state and applies an absolute change, including after an uncertain acknowledgement. At most two attempts occur, throttling is respected, unowned remote fields are preserved, and Room advances only after remote confirmation. Existing pull synchronization calls `refreshRate`; it neither imports a remote library nor creates synthetic local manga or Favorites.

The Library Group picker explicitly retains its prior five-provider behavior. MangaUpdates uses regular manga tracking for this phase. Broad remote-library import and group tracking are separate product capabilities, not prerequisites for a usable regular tracker.

## Validation boundaries

Official OpenAPI and unauthenticated public series/author/rating-distribution reads were inspected. Authenticated account responses and writes are covered by deterministic terminal-transport fixtures; no provider credentials enter tests. Android checks cover Room association/adoption/progress, retries, synchronization, backup, encrypted session recreation/corruption/logout and native login lifecycle. JVM checks cover DTO semantics, large IDs, session rejection, bearer containment, queue bounds/coalescing and write timing. Exact-head CI results are recorded in PR #563 after execution.

Live authenticated login, remote writes and physical-device behavior remain NOT VERIFIED. B1/B2 tests and current core Details, Chapters, Reader, Library, Downloads, Smart Local, database migration and backup behavior are preserved. Features #1–#5 and unrelated Batch 11/12 work are outside this implementation.
