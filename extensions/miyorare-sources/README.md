# Miyorare Source Packs (staging)

This directory is the reproducible staging area for the official Miyorare Tsuki source packs:

- `miyorare-id` — Indonesian sources
- `miyorare-en` — English sources

The packs are **not bundled into the Miyorare APK**. They target the existing Tsuki 1.0.5 plugin
runtime and are intended to be installed on demand under the `MIYORARE` provider identity.

## Why this is separate from the app

Source websites change much more often than the Miyorare core. Keeping parser packs separate lets
source fixes ship without touching Reader, Downloads, Explore, Favourites, startup, or navigation
code. Installing a large pack also does not enable every source automatically; the host keeps source
visibility as an explicit user choice.

## Pack composition

Each pack keeps the explicitly curated UMA sources in `packs.json` as the authoritative base. The
build then imports **every Gekkoushi parser for the same pack language** (`id` or `en`) from the
exact pinned `Gekkoushi/plugin-source` commit.

Duplicate handling is based on the runtime `@MangaSourceParser` source key, not display text:

1. prepare the curated UMA base;
2. scan all Gekkoushi parser declarations for the target language;
3. skip a Gekkoushi parser when its runtime source key already exists in the UMA base;
4. keep every other Gekkoushi source for that language;
5. fail the build on mixed locales, duplicate runtime keys, or an unexpected source-set mismatch;
6. compile the merged tree into one dexed Miyorare pack JAR.

This keeps the sources already accepted into Miyorare while adding the rest of Gekkoushi without
showing duplicate providers inside the same official pack.

## Reproducibility

`packs.json` pins both upstream repositories to exact commits:

- `InvalidDavid/UMA` supplies the existing curated base;
- `Gekkoushi/plugin-source` supplies the all-source ID/EN expansion.

`tools/prepare_pack.py` prepares the UMA whitelist. `tools/prepare_gekkoushi_merge.py` performs the
language-wide Gekkoushi intake and duplicate filtering. `tools/finalize_pack.py` verifies KSP output,
embeds both GPL license texts plus exact provenance metadata, and writes the final SHA-256.

The generated `*-pack.json` records the final runtime source list, how many sources came from the
curated UMA base, how many new Gekkoushi sources were added, and which Gekkoushi runtime keys were
skipped because Miyorare already had them.

## M2 multi-upstream intake

`multi-upstream.json` is a stricter compatibility manifest for selected sources that exist across
multiple providers. It does **not** infer equivalence from a display name. The M2 intake verifies
source identity against pinned upstream data before an alias can own a canonical Miyorare source ID.

Keiyoushi APK extensions remain usable through the Mihon-compatible runtime. UMA, Gekkoushi and
official Miyorare packs use the Tsuki-compatible path. Miyorare does not add another loader just to
expand these packs.

## Download compatibility

Changing provider does not move or rewrite existing download files. Miyorare's compatibility layer
uses strong identity evidence and stores non-destructive aliases to existing local paths. Ambiguous
matches are not auto-linked.

## Licensing

See `ATTRIBUTION.md`. UMA and Gekkoushi parser code are GPL-3.0 and their exact license/provenance is
preserved in staging artifacts. Keiyoushi compatibility metadata retains its Apache-2.0 provenance.
Parser availability does not imply affiliation with third-party websites or ownership of their
content.
