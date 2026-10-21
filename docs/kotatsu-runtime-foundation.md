# Kotatsu Runtime Pack foundation (tasks 1–6)

This document records the decision-quality evidence for the first six implementation tasks of the isolated Kotatsu-Redo integration. It is intentionally limited to repository state, upstream/build facts, ABI coexistence, Android class-loader proof, and the Miyorare-owned protocol boundary. Catalog/UI/updater/publisher completion belongs to later tasks in the same PR.

## 1. Repository checkpoint

At the start of this phase:

- target branch `Amain2`: `6829c1d601adef4d773b47617c68c20d23737b70`
- work branch / PR #693 head before this phase: `0d275252ed4e98dad01a6093cfc0ad3ac3aa5723`
- PR base: `Amain2`
- the exact pre-phase head passed `PR Compile & Unit Check`
- PR labels: `ci:owner-request`, `ci:runtime-required`

Any validation after this document is committed must be tied to the new exact PR head, not the older successful SHA.

## 2. Kotatsu-Redo upstream checkpoint

The inspected upstream is `Kotatsu-Redo/kotatsu-parsers-redo` master at:

`dc223c3d8098415b29cf1acfedc401a88d01c9fe`

It is a JVM parser library (Java/Kotlin library + KSP-generated sources), not a ready-to-download Android runtime APK. Therefore Miyorare must build its own runtime artifact around a pinned upstream revision.

Relevant upstream dependency versions at this checkpoint include Kotlin 2.2.10, coroutines 1.10.2, OkHttp 5.1.0, Okio 3.16.0, AndroidX Collection 1.5.0, and Jsoup 1.21.2. The Miyorare app currently uses newer/different versions for several of these. This is another reason not to link the upstream parser library directly into the app process class path.

## 3. Existing Miyorare distribution infrastructure

Miyorare already has the pieces needed for a controlled runtime-pack publisher:

- GitHub Actions with exact-ref checkouts and artifact stages
- upstream/source-pack compatibility workflows
- permanent APK release signing secrets in the stable build workflow
- GitHub Release publication from Actions
- exact-head PR compile/unit validation and an Android 15 runtime lane selected by `ci:runtime-required`

The Kotatsu runtime should use a dedicated workflow/artifact stream rather than coupling routine parser updates to the stable Miyorare APK release. The existing release/source-pack workflows are implementation patterns, not a reason to make Kotatsu part of Miyorare Pack.

## 4. Exact Gekkoushi/Kotatsu ABI overlap

Miyorare currently pins Gekkoushi/plugin-source at:

`21d4b79b5fe2a7e124b63c478c15b1cc79cc5c16`

Both Gekkoushi and Kotatsu-Redo define `org.koitharu.kotatsu.parsers.*`. Several top-level parser classes are byte-for-byte identical at the inspected revisions (`Broken`, `ErrorMessages`, `InternalParsersApi`, `MangaParser`, `MangaParserAuthProvider`, and `MangaSourceParser`), while important ABI surfaces are not.

`MangaLoaderContext` differs materially. Gekkoushi has the two-argument `evaluateJs(baseUrl, script)`, while Kotatsu-Redo uses a timeout argument and additionally exposes Cloudflare verification and WebView interception/capture APIs. The source blobs differ (`438cb1a596eb96616b97a5eb010b8efdc2890377` vs `ba9bbbb0aabc8a4c399bf0200aefc4ebcff16717`).

`ConfigKey` also differs (`cd233f1d9e57eb28a544c1e4f9cb87242f63f078` vs `7af9e7980e6e76a47f4e7b4614f52ffdbee3f750`): Kotatsu-Redo adds `DisableUpdateChecking` and `InterceptCloudflare`. `MangaSourceConfig` itself is still byte-identical at `d4e11c49f1665937f994b632949f7d5c01fd2680`.

The core manga model files inspected at this checkpoint, including `Manga`, `MangaChapter`, and `MangaListFilter`, retain matching blob identities between the two repositories. That partial compatibility does **not** make direct co-linking safe because class identity is per class loader and the changed classes keep the same FQCNs.

Decision: do not add Kotatsu-Redo as a second raw app dependency, do not suppress duplicate-class checks, and do not replace Gekkoushi. The runtime pack owns its parser ABI and dependency closure inside a separate `DexClassLoader`.

## 5. Android isolation proof

`KotatsuRuntimeIsolationTest` is an on-device instrumentation proof, not a JVM simulation. The instrumentation APK deliberately contains a second class named `org.koitharu.kotatsu.parsers.MangaLoaderContext` plus the runtime entry point. The test then installs that APK as a candidate runtime pack through the real verifier/store/manager path.

The runtime entry point's health check succeeds only when:

1. the runtime `MangaLoaderContext` and host `MangaLoaderContext` are different `Class` objects,
2. the runtime copy was loaded by the child runtime loader,
3. the host copy remains owned by the parent app loader, and
4. a method that exists only on the fixture copy is callable from the runtime copy.

The test also requires the candidate APK to pass the real SHA-256 and signing-certificate checks and verifies that the catalog crosses back into the app only as JSON and becomes the stable host identity `KOTATSU:FIXTURE`.

This test is wired into the existing Android 15 `ci:runtime-required` lane. A green exact-head Android run is required before this proof is considered established. It is still emulator evidence, not physical-device validation.

## 6. Miyorare-owned host protocol v1

Protocol v1 intentionally does not expose `MangaLoaderContext`, `MangaParser`, `ConfigKey`, Kotatsu model objects, OkHttp objects, or any other runtime-owned type to Miyorare. Reflection crosses only Android/Java bootstrap types, primitives, `String`, and JSON. The entry point receives Android `Context` only as a bootstrap capability; the downloaded runtime is already trusted executable code because it must carry the same accepted signing identity. Context is therefore not being treated as a security sandbox boundary.

The stable v1 operation names are centralized in `KotatsuRuntimePackContract.Methods`:

- `protocolVersion()`
- `healthCheck()`
- `catalogJson()`
- `getListJson(sourceKey, offset, orderName, filterJson)`
- `getDetailsJson(sourceKey, mangaJson)`
- `getPagesJson(sourceKey, chapterJson)`
- `getPageUrl(sourceKey, pageJson)`
- `getRelatedJson(sourceKey, mangaJson)`
- `sortOrdersJson(sourceKey)`
- `filterCapabilitiesJson(sourceKey)`
- `filterOptionsJson(sourceKey)`
- `sourceConfigJson(sourceKey)`
- `setSourceConfigJson(sourceKey, valuesJson)`
- `requestHeadersJson(sourceKey)`

This operation set is derived from Miyorare's current `MangaRepository` contract plus Kotatsu parser configuration. Rich filters are carried as Miyorare-owned JSON rather than reduced to a search string. Source configuration is likewise represented as a generic JSON schema/value map so future upstream `ConfigKey` subclasses do not become host ABI.

Persisted source identity is outside the runtime protocol: the host owns `KOTATSU:<stable-source-key>`. Parser revision, runtime pack version, display title, domains, and upstream enum/list positions are not part of that identity.

Binary image transforms and browser/Cloudflare host-service details are deliberately not frozen into protocol v1 during tasks 1–6. They require the runtime adapter implementation to prove which capabilities are actually needed. Adding an upstream parser-specific object here would defeat the isolation goal; if a new cross-boundary capability is required it must use platform/primitive/JSON data and a protocol-versioned addition.
