# Issue #531 Phase 2 — B2 Character & Staff

Continues B1 `f4c45a192d53636d1db776334f4b05af95ea4588` on
`feat/issue531-tracker-details-phase2`; integration baseline is
`Amain2@fd996867f0b4c15e66d248d7ecd1a7bbc397f594`. PR #563 remains draft,
unmerged, for the same B1/B2/B3 lineage.

## Screen ownership

Details observes raw persisted associations through the read-only
`ScrobblingDao.observeAll(mangaId)` query. Provider selection uses those rows,
the existing repository map and B1 capability/auth gates. Equal numeric IDs across
providers remain distinct. No title search, association write or discovery exists.

The narrow `DetailsPeopleController` owns one demand-driven flight in
`viewModelScope`, sequentially reading one associated provider at a time. Only
Characters and Staff first pages are requested through B1. Providers publish
independently, so successful data remains visible while another provider loads
or fails. Retry targets one failed provider; refresh invalidates supplemental
metadata separately from the existing core loader. Repeated lazy composition
coalesces demand. Completed providers are retained across stop/resume; unfinished
work is cancelled and resumed. ViewModel destruction cancels all remaining work.

Identity contains screen manga/source/URL, association identity (excluding progress
updates), authorization and in-memory session generations. Context changes clear
old data. Cancellation plus a generation ticket rejects late/noncooperative
responses. Before and after each provider, current Room associations/private-only
membership, settings, manga/source and session generations are rechecked. Unknown
privacy/identity fails closed. Nothing joins core Details loading/error counters.

## Privacy and accounts

Incognito, Private-space Details, private-only membership, Local Files and Smart
Local suppress optional reads. NSFW Incognito ASK is conservatively suppressed as
well as ENABLED. Privacy observers remain alive while the screen is stopped;
Activity rendering additionally hides people while private classification is
unknown/private or HistoryInfo is Incognito. Portrait composition disappears with
the metadata, cancelling its screen-owned image request.

Existing `ScrobblerStorage` adds only an in-memory epoch on access-token replacement,
account-ID change and logout. Four verified B1 providers expose that epoch through
their optional details contract. Token refresh conservatively invalidates people
too. No token values enter screen identity, logging, tests or metadata caches.
Existing credential persistence/authentication is not migrated. The epoch is not
persisted or backed up; restored screens begin with no people metadata.

## Presentation and provider limits

Characters and Staff are inserted immediately after the chapter block in the
existing Details LazyColumn. Provider attribution is in each heading. Names and
provider-supplied roles render with optional HTTPS portraits; missing images use a
neutral initial and an accessibility description, without an invented portrait.
No parser image extras/auth headers are attached. Ten records are initially
available per horizontal lane; local Show more exposes the remaining records
already returned by B1. No automatic pagination is added. Partial/next-page results
are marked as available metadata rather than complete catalogs.

AniList, Shikimori and Kitsu support Characters and Staff. MAL shows only verified
authors/person roles without fabricated portraits/Characters. Empty/unsupported/
unmapped/signed-out sections remain absent. Provider errors show safe local retry
text and retain other valid content. MangaBaka remains unverified/excluded;
MangaUpdates remains HOLD. B1 Recommendations are unchanged and unused by Details.

## Validation surface

Deterministic JVM tests exercise demand/coalescing, staff-only/success/empty/partial,
provider isolation/retry, provider-scoped equal IDs, manga/source/association/session
invalidation, privacy transitions, cancellation, stop/resume and refresh. Existing
B1 and Details tests remain unchanged.

Compose instrumentation tests render real people components with a fixture-only
image loader (no network), testing image/name/role, missing images, absent neutral
sections, partial/error/retry, core sibling content and removal of private results.
Android storage tests verify epoch changes and unchanged persistence format.
The existing Amain2 PR workflow adds these two classes to its existing Android 15
session, preserving Smart Local cover/image-cache guards and also executing existing
ChapterPersistenceRegressionTest and LocalBackupIdentityTest. Compose test-only
dependencies use the existing BOM; no production dependency is upgraded.

Exact commands/results and the remote continuation SHA are reported with the final
B2 handoff. Runtime regression coverage is emulator evidence, not physical-device
or authenticated live-provider testing. No recommendation UI/navigation, Feature #8,
DB migration, backup-schema change, or intentional Batch 11/12 behavior change is
part of B2. Do not begin B3 or merge this staged PR as part of B2.
