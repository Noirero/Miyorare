# Issue #531 Phase 2 — B1 foundation

Baseline: `Amain2` / `feat/issue531-tracker-details-phase2` at
`fd996867f0b4c15e66d248d7ecd1a7bbc397f594`.

This checkpoint is non-visual. Existing provider repositories implement an optional
`TrackerDetailsProvider`; the existing `ScrobblerRepositoryMap` selects them.
`ReadTrackerDetailsUseCase` reads existing `ScrobblingDao.findAll` associations and
uses `(service, targetId)`, never the local manga id or rate id. An ambiguous or
invalid association cannot authorize a guessed replacement. No write API is called.

| Provider | Characters | Staff | Per-title recommendations |
| --- | --- | --- | --- |
| AniList | Yes, edge roles | Yes, edge roles | Media recommendation identity |
| MAL | Unsupported | Documented authors/roles, no person images | Manga recommendations |
| Shikimori | GraphQL characterRoles | GraphQL personRoles | Similar Manga provenance |
| Kitsu | mediaCharacters + included characters | mediaStaff + included people | Unsupported |
| MangaBaka | Unverified / excluded | Unverified / excluded | Unverified / excluded |
| MangaUpdates | HOLD / excluded | HOLD / excluded | HOLD / excluded |

## Consumption contract for B2/B3

- Supply only demanded services and capability/page pairs. Each call executes
  sequentially, at most one provider request at a time; no background scope exists.
- The default read policy suppresses optional traffic. Explicitly enable it only
  after checking incognito, private-only and on-device/local exclusions.
- Authorization is a product gate even for providers with public catalog APIs.
  Logout discards results. The future screen consumer owns cancellation and
  generation checks on source, mapping, account and privacy changes; B1 installs
  no screen consumer or lifecycle hooks.
- Results distinguish not-requested, unsupported, empty, success, partial and error.
  A capability failure does not discard another capability/provider's data.
  Coroutine cancellation propagates. Error causes remain diagnostic, without
  adding response-body or credential logging.
- AniList returns one requested connection page (25 edges). Kitsu returns one
  related collection page (at most 20), preserving validated response next links.
  Kitsu links cannot change origin, collection or target; no included-resource
  N+1 requests are generated. Missing linkage yields usable partial data.
- MAL embedded collections and Shikimori role/similarity arrays have no verified
  independent pagination here. Conversion is limited to 25 records; truncation is
  explicit Partial, without an invented next cursor. These endpoints can return
  larger single responses. Do not claim complete catalog coverage.
- Person roles merge only by a supplied identity within one provider/section.
  Recommendation identities include the provider. Target URL is provenance, not
  part of `TrackerTarget.identity`.
- No memory metadata cache is added: B1 has no active UI consumer, and adding
  account/visibility generations prematurely is unnecessary. Later consumers must
  coalesce demand and can assess a bounded cache once lifecycle ownership exists.

## Scoped host verification

Anonymous reads on 2026-10-09 verified `.one` similar redirects to `.io`, `.io`
GraphQL roles and `.io` Similar Manga. Supplemental Shikimori uses a public,
credential-free client at `.io`, with normal TLS, finite timeouts, no cookie store,
no logger and redirects disabled. Legacy `.one` OAuth/tracking is unchanged.
This scoped public client does not inherit the application's custom proxy/DoH
configuration. Kitsu related collections at `kitsu.app` returned current
mediaCharacters/mediaStaff plus matching included resources and collection links.

Fixture tests need no network or account credentials. Local isolated JVM execution
checks converters and the read helper using temporary Android/DI/parser seams;
it is not a substitute for full Android compilation, Hilt or repository CI.
The current Amain2 PR workflow supplies full compile/resources/JVM validation and,
with `ci:runtime-required`, existing Android 15 cover/cache regression guards.
Those runtime guards do not establish tracker UI or physical-device acceptance.

No Details UI, recommendation navigation, Feature #8, DB migration, credential
migration, or intentional Batch 11/12 behavior change belongs to this checkpoint.
