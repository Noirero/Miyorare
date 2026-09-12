# Miyorare Sources — Attribution

This directory stages Miyorare-owned logical source packs and compatibility metadata. It does not
claim ownership of the third-party websites exposed by a parser.

## UMA / Tsuki parser code

The curated UMA shard of each Miyorare-ID / Miyorare-EN logical pack is built from source code in
**InvalidDavid/UMA**, pinned to the exact commit recorded in `packs.json`. UMA is distributed under
**GNU GPL v3.0**.

Upstream repository: `https://github.com/InvalidDavid/UMA`

UMA is built with UMA's own source tree, dependencies, Gradle configuration and KSP process. Its
license and exact build provenance are embedded in the resulting `*-uma.jar` shard.

## Gekkoushi parser code

The Gekkoushi shard is built independently from **Gekkoushi/plugin-source**, pinned to the exact
commit recorded in `packs.json`. Gekkoushi/plugin-source is distributed under **GNU GPL v3.0**.

Upstream repository: `https://github.com/Gekkoushi/plugin-source`

Gekkoushi is built with its own unmerged source tree, helpers, dependencies, Gradle configuration and
KSP process. Its license and exact build provenance are embedded in the resulting `*-gekkoushi.jar`
shard.

For each language, Miyorare exposes the existing curated UMA runtime source keys first and adds only
matching-language Gekkoushi runtime source keys that UMA does not already provide. Duplicate
filtering is performed on the `@MangaSourceParser` runtime key, not on a display name. No Gekkoushi
source is copied into UMA and no UMA source is copied into Gekkoushi.

The logical `miyorare-<lang>-pack.json` manifest records both shards, their exact upstream revisions,
the final unique source list, and Gekkoushi keys omitted because UMA is authoritative for those keys.

## Keiyoushi intake

M2 also verifies selected compatibility candidates against **keiyoushi/extensions-source**, pinned
to the exact commit recorded in `multi-upstream.json`. Keiyoushi's source repository is distributed
under **Apache License 2.0**.

Upstream repository: `https://github.com/keiyoushi/extensions-source`

The compatibility intake consumes metadata to prove cross-provider identity. Keiyoushi APK
extensions remain a separate Mihon-compatible runtime and are not copied into either Tsuki shard. If
Miyorare later adapts Keiyoushi code directly, the Apache-2.0 license and required notices must be
retained alongside the applicable Miyorare distribution obligations.

## Content providers

Parser/source support does not imply affiliation with, endorsement by, or ownership of the websites
or content providers that a parser can access. Users remain responsible for complying with
applicable provider terms and local law.
