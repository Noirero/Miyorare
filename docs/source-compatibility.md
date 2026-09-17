# Miyorare Source Compatibility Foundation

This document defines the invariants for the Miyorare-owned source ecosystem. The implementation must preserve these rules while Miyorare-ID and Miyorare-EN are introduced.

## Goals

- Keep the app core fast and usable when no optional provider is installed.
- Support the existing Mihon/Keiyoushi-compatible APK path, Tsuki/Usagi JAR path (including UMA/Gekkoushi), LNReader plugins, and Kotatsu parsers without creating another competing loader.
- Add an official `MIYORARE:` namespace for future Miyorare source packs.
- Treat provider identity and user content identity as different concepts.

## Canonical identity

Persisted provider keys remain unchanged. `SourceAliasRegistry` computes a provider-neutral canonical identity in memory.

Current safe mappings:

- `MIHON_<sourceId>:...` -> `catalogue:<sourceId>`
- a legacy Kotatsu source that `KotatsuSourceMap` maps to that same Mihon source -> `catalogue:<sourceId>`
- `TSUKI:...` -> provider-local identity until an explicit cross-provider alias is supplied
- `LN_...` -> provider-local identity
- `MIYORARE:...` -> official Miyorare identity

Display names are never used as proof that two sources are identical.

## User-data invariants

Changing or disabling a provider must not by itself:

- delete or move CBZ/ZIP/PDF/EPUB files;
- trigger a redownload;
- delete favourites, categories, notes, history, bookmarks, or reading progress;
- turn a downloaded chapter unreadable solely because its remote chapter disappeared;
- silently rewrite a stored source key before a migration has been validated.

Provider migration must resolve existing content in this order where data is available:

1. canonical content/source identity;
2. explicit source alias;
3. stable/public manga URL or provider content key;
4. download metadata;
5. existing legacy folder/path metadata;
6. normalized title + chapter fallback;
7. preserve as an unlinked/orphaned download and offer manual reconnect.

The fallback stages must be non-destructive. A weaker match may suggest a reconnect but must not delete the original data.

## Performance rules

- Source identity resolution is metadata-only and must not load plugin classes or perform network requests.
- Tsuki runtime remains lazy; do not add another JAR class loader.
- Existing Mihon extension runtime remains the Keiyoushi-compatible APK path; do not duplicate it with a second APK loader.
- Large source packs must opt sources into Explore/global search rather than enabling every parser automatically.

## Source packs

The intended official packs are initially:

- `Miyorare-ID`
- `Miyorare-EN`

They should be shipped separately from the app APK and use the existing Tsuki-compatible plugin host unless a later measured requirement proves that a different ABI is necessary. A source pack update should not require a Miyorare app update when the host ABI remains compatible.

Third-party code keeps its original license and attribution. Copying/adapting a parser does not remove those obligations.

## Rollout

1. Canonical identity + alias registry (this foundation).
2. Use canonical identities in source migration/reconnect UI.
3. Add download resolver metadata/fallbacks without moving existing files.
4. Add official Miyorare provider metadata/signing/update channel.
5. Build small EN/ID packs by adapting proven parsers, then expand via compatibility tests rather than enabling every imported source at once.
6. Add explicit cross-provider aliases only when the equivalence is verified; never infer them from a display name alone.
