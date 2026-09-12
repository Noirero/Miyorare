# Miyorare Source Packs (staging)

This directory is the reproducible staging area for the first official Miyorare Tsuki source packs:

- `miyorare-id` — curated Indonesian sources
- `miyorare-en` — curated English sources

The packs are **not bundled into the Miyorare APK**. They target the existing Tsuki 1.0.5 plugin
runtime and are intended to be installed on demand under the `MIYORARE` provider identity.

## Why this is separate from the app

Source websites change much more often than the Miyorare core. Keeping parser packs separate lets
source fixes ship without touching Reader, Downloads, Explore, Favourites, startup, or navigation
code. Installing a large pack also does not enable every source automatically; the host keeps source
visibility as an explicit user choice.

## Reproducible UMA pack import

`packs.json` pins the exact UMA repository and commit. `tools/prepare_pack.py` verifies that checkout
before removing every non-target language and every source file not present in the curated whitelist.
Shared parser/util code is retained because many small source declarations depend on it. KSP then
generates the Tsuki source enum/factory from only the remaining annotated source classes.

A source is never added merely because it appears upstream. It must first be added to a pack's
whitelist and pass the pack build. Runtime health/network behavior is a separate acceptance gate
before the pack is treated as stable.

## M2 multi-upstream intake

`multi-upstream.json` is a second, stricter manifest for sources that exist in both UMA/Tsuki and
Keiyoushi. It does **not** infer equivalence from a display name. Every entry pins:

- the exact UMA source file and runtime source key;
- the exact Keiyoushi module and deterministic/explicit source id;
- one verified website host;
- the official Miyorare pack/source identity that owns the canonical id.

`tools/verify_multi_upstream.py` checks those facts against pinned upstream checkouts and emits a
normalized intake artifact in CI. Only aliases that pass this check are allowed into
`VerifiedSourceAliases`.

The first POC intentionally contains only six verified overlaps:

- ID: Bacami, Kiryuu, Komiku
- EN: Asura Scans, AquaReader/Aqua Manga, BatCave

Keiyoushi APK extensions remain usable through the existing Mihon-compatible runtime. UMA and
official Miyorare packs remain usable through the existing Tsuki runtime. M2 links only their
verified identities; it does not add another APK/JAR loader and it does not auto-transpile arbitrary
Keiyoushi source code into Tsuki source code.

## Download compatibility

Changing provider does not move or rewrite existing download files. Miyorare's core compatibility
layer resolves strong identity evidence and stores a non-destructive alias from the new remote manga
ID to the existing local path. Ambiguous matches are not auto-linked.

## Licensing

See `ATTRIBUTION.md`. UMA-derived staging code keeps its GPL-3.0 provenance. Keiyoushi intake
metadata points at Apache-2.0 upstream modules and preserves that provenance. Importing/adapting a
source never removes its original license/notice obligations. Parser availability does not imply
affiliation with third-party websites or ownership of their content.
