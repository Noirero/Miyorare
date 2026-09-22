# Miyorare Audio Pack

This directory defines the audio-extension catalog consumed by Hiraukan.

It is intentionally separate from `extensions/miyorare-sources`, whose current
runtime contract is Tsuki/JAR and manga-specific.

## Schema v1

Schema v1 supports `delivery.kind = "builtin"` only. The pack therefore
controls discovery, enable/disable/install state and compatibility for audio
extensions that are already shipped in Hiraukan. It does **not** download or
execute unsigned Dart/native code.

This gives Hiraukan a safe migration path:

1. Miyorare Pack declares an audio extension.
2. Hiraukan verifies that the declared `runtimeId` exists locally.
3. Install enables that runtime.
4. Remove disables it without deleting Hiraukan code.
5. A future schema may add signed external delivery after a dedicated trust
   model and rollback contract are implemented.

The first reference extension is `miyorare.audio.asmr_one`.

Additional Hiraukan built-in audio runtimes:

- `miyorare.audio.japanese_asmr` — JapaneseASMR; catalog/search/detail/playback/download.
- `miyorare.audio.asmr18` — ASMR+18 (boys catalog); catalog/search/detail/playback via direct/HLS media.
- `miyorare.audio.asmr_hentai_net` — ASMR Hentai; catalog/search/detail only until its media API contract is verified.
