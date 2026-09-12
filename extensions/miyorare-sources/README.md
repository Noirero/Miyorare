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

## Reproducible upstream import

`packs.json` pins the exact upstream repository and commit. `tools/prepare_pack.py` verifies that
checkout before removing every non-target language and every source file not present in the curated
whitelist. Shared parser/util code is retained because many small source declarations depend on it.
KSP then generates the Tsuki source enum/factory from only the remaining annotated source classes.

A source is never added merely because it appears upstream. It must first be added to a pack's
whitelist and pass the pack build. Runtime health/network behavior is a separate acceptance gate
before the pack is treated as stable.

## Download compatibility

Changing provider does not move or rewrite existing download files. Miyorare's core compatibility
layer resolves strong identity evidence and stores a non-destructive alias from the new remote manga
ID to the existing local path. Ambiguous matches are not auto-linked.

## Licensing

See `ATTRIBUTION.md`. Upstream GPL source and license/provenance are preserved in staging artifacts.
Parser availability does not imply affiliation with third-party websites or ownership of their
content.
