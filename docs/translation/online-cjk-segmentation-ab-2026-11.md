# Miyorare Online CJK segmentation — controlled quality gate (9 Nov 2026)

**Status: EXPERIMENTAL. Production Online and BYOK code paths remain unchanged.**

## Source-of-truth baseline
- Integration: Noirero/Miyorare Amain2 @ 93eda83b0ab24bfdb3f22d9a95e9d77cf1167775.
- Current NovelTranslationCoordinator -> NovelTranslationSegmenter.split -> MiyorareOnlineTranslationEngine -> translate.googleapis.com/translate_a/single (client=gtx; dt=t; text via q).
- Online 1600 UTF-16 code units, concurrency 1, request gap >=750ms; retries and circuit breaker in Coordinator.
- Memory and persistent semantic keys contain provider ID, configuration fingerprint, provider contractVersion, languages, style, context, and segment digest; historical Online contract is online-v1. No wholesale cache purge.

## Hypothesis and scope
Current segmentation finds .!?。！？ endings only when followed by whitespace; CJK narrative often omits whitespace. The isolated splitCjkAware candidate finds sentence boundaries without space, attaches closing dialogue quotes, recognizes CRLF/Unicode paragraphs and minimizes surrogate/combining/ZWJ/CRLF splits.

**Production split() and its Coordinator call are deliberately unchanged.**
Do not touch BYOK provider, prompts, selection, style, or billing.

## Corpus and methodology
The fixed resource app/src/test/resources/novel-translation-quality/ja-zh-ko-v1.json contains **24 original synthetic literary excerpts** (8 Japanese, 8 Mandarin, 8 Korean; approximately 2000–2200 UTF-16 code units). This is a controlled proxy, **not** a representative sample of all user novels. Cases cover dialogue, no-space CJK, paragraph/newline controls, named entities, pronoun/reference and punctuation.

The opt-in Kotlin JUnit NovelOnlineCjkQualityABBenchmarkTest calls the **actual MiyorareOnlineTranslationEngine** with exactly the same text, source/target language and configuration for both strategies. The Online request limit is 1600 UTF-16 units; calls are serial with >=750ms delay; A/B order alternates by sample. No user content or user cache is involved.

Artifacts:
- app/build/translation-quality-ab/blind-paired-translations.json — original, A/B outputs, segment boundaries, request count and duration.
- app/build/translation-quality-ab/unblind-map.json — mapping from A/B to legacy or candidate. **Never open this file before blind quality scoring.**

The live test skips unless MIYORARE_ONLINE_TRANSLATION_AB_LIVE=1. Successful execution alone never establishes quality.

### Blind human quality review (bilingual JP/ZH/KO reviewers)
Rate A and B independently from 0–5 for each excerpt:
1. Meaning fidelity (35%): facts, roles, time, negation.
2. Character/place names (20%): proper names, reference stability.
3. Dialogue (15%): speaker attribution, quotes, honorific/register.
4. Indonesian narrative fluency (20%): natural, coherent syntax.
5. Terminology and cross-boundary consistency (10%).

For a score gap >=1, annotate a concrete error rather than a preference-only judgment. Unblind only after scores are recorded. Summarize paired deltas by language and combined; repeat conflicting cases with a second reviewer. Synthetic samples and temporal drift of Google remain limitations.

Suggested gate (owner can adjust): median paired weighted score improves >=0.25/5 overall; no language decreases >0.25; zero material semantic/dialogue regressions; median elapsed time and request count increase <=15%; no new rate-limit failures. If inconclusive, keep production as is.

### Reliability
- HTTP 403/429/timeouts abort as INCOMPLETE_NOT_COMPARABLE; do not score incomplete pairs or claim quality uplift.
- Alternating request order mitigates but does not eliminate backend model drift.
- Tag results with exact HEAD, run ID, date and source/target language.
- Offline JVM assertions independently check lossless reconstruction, UTF-16 segment limits, CRLF, punctuation and Unicode.

## Cache and rollout rule
**No online contract or cache state changes in this experiment.** If blinded evaluation proves uplift and the owner authorizes rollout, change the Online-only splitter and bump only the online provider semantic identity (e.g. online-v2-cjk for both contractVersion and fingerprint input). Old/new memory and persistent keys then remain disjoint without deleting existing caches or touching BYOK. Preserve existing durable cache file schema (novel-translation-semantic-v2). Add an exact-key regression at rollout.

## Terminology
Collect actual named-entity failures (e.g. 美咲 / 林晨 / 지윤) from A/B before designing any vocabulary protection. Do not introduce an extensive glossary or change BYOK absent evidence.

## UNKNOWN before production
Live complete 24-pair outcome, independent blind semantic ratings, long chapter runtime latency, Android 15, physical-device reading/navigation, EPUB translation and real-user caches after future provider contract rollout. No merge/release.
