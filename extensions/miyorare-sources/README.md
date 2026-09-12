# Miyorare Source Packs (staging)

This directory is the reproducible staging area for the official logical Miyorare source packs:

- `miyorare-id` — Indonesian sources
- `miyorare-en` — English sources

The packs are **not bundled into the Miyorare APK**. They target the existing Tsuki 1.0.5 plugin
runtime and remain optional so source maintenance cannot slow unrelated core app paths.

## Logical pack, independent build shards

A Miyorare language pack is one user-facing catalog but it is not one giant source tree or JAR.
Each logical pack is composed from independently built upstream shards:

```text
Miyorare-ID
├── miyorare-id-uma.jar
└── miyorare-id-gekkoushi.jar

Miyorare-EN
├── miyorare-en-uma.jar
└── miyorare-en-gekkoushi.jar
```

UMA is compiled with UMA's own helpers, Gradle configuration and KSP process. Gekkoushi is compiled
with Gekkoushi's own helpers, dependencies, Gradle configuration and KSP process. Source code from one
upstream is never copied into the other upstream's source tree.

This separation is intentional: Tsuki compatibility does not imply identical internal helper APIs,
dependency graphs, generated code or package layout.

## Deduplication policy

The existing curated UMA runtime source keys remain authoritative inside the official logical pack.
Gekkoushi contributes every matching-language source key that UMA does not already provide.

Deduplication uses the runtime `@MangaSourceParser` key, not display text:

```text
logical pack = curated UMA + (matching-language Gekkoushi - UMA runtime keys)
```

A duplicate Gekkoushi implementation is not exposed by the Miyorare logical pack, but the original
Gekkoushi project is otherwise built intact. This avoids breaking shared/multisource parser families.

## Build flow

For each `id` and `en` pack CI performs these steps:

1. checkout the exact pinned UMA and Gekkoushi revisions;
2. prepare and build the curated UMA shard using UMA;
3. scan Gekkoushi metadata and compute the matching-language non-duplicate allowlist;
4. build Gekkoushi unchanged using Gekkoushi;
5. finalize each dexed shard with its own upstream GPL license and exact provenance;
6. create `miyorare-<lang>-pack.json`, which joins the two shards into one logical catalog and proves
   that their exposed runtime keys do not overlap.

`tools/prepare_pack.py` prepares the UMA shard.
`tools/prepare_gekkoushi_shard.py` prepares Gekkoushi visibility/provenance without modifying its
source code. `tools/finalize_pack.py` validates each physical shard. `tools/finalize_logical_pack.py`
validates the final logical catalog.

## Hidden Gekkoushi support entries

Gekkoushi may need source enum constants outside ID/EN because shared parser classes reference them.
Those entries may remain compiled inside the physical Gekkoushi shard, but
`META-INF/miyorare-pack.json` exposes only the intended language's non-duplicate source keys. The
Miyorare Tsuki probe/runtime treats that metadata as the official source allowlist.

## Multi-upstream compatibility

`multi-upstream.json` remains the stricter canonical-identity layer for selected sources that exist
across providers. It never treats matching display names alone as proof of equivalence.

Keiyoushi APK extensions remain usable through the Mihon-compatible runtime. UMA/Gekkoushi shards use
the Tsuki-compatible runtime. These runtimes are allowed to coexist; Miyorare's canonical source and
download compatibility layers sit above them rather than forcing their implementation code together.

## Download compatibility

Changing a verified equivalent provider must not move, delete or rename existing downloaded files.
Miyorare resolves strong canonical/source aliases and legacy paths non-destructively. An ambiguous
match is not auto-linked and a provider change alone must not trigger a redownload.

## Licensing

See `ATTRIBUTION.md`. UMA and Gekkoushi parser code are GPL-3.0; each shard preserves the exact
upstream license and provenance used to build it. Keiyoushi compatibility metadata retains its own
upstream attribution. Parser availability does not imply affiliation with third-party websites or
ownership of their content.
