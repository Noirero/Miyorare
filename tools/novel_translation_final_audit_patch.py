from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count} for anchor:\n{old[:240]}")
    file.write_text(text.replace(old, new))


reliability = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationReliability.kt"
coordinator = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationCoordinator.kt"
projection = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationProjection.kt"
reader = "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt"
reliability_test = "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationReliabilityTest.kt"
coordinator_test = "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationCoordinatorTest.kt"
projection_test = "app/src/test/kotlin/org/koitharu/kotatsu/reader/ui/epub/translation/NovelTranslationProjectionTest.kt"

# 1) Cache corruption remains recoverable but becomes typed/observable.
replace_exact(
    reliability,
    """object NoOpNovelTranslationResultCache : NovelTranslationResultCache {\n\toverride suspend fun read(key: NovelTranslationSemanticKey): String? = null\n\toverride suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean = false\n}\n\n/** App-private, bounded persistent segment-result cache. Source text and provider secrets are never stored. */""",
    """object NoOpNovelTranslationResultCache : NovelTranslationResultCache {\n\toverride suspend fun read(key: NovelTranslationSemanticKey): String? = null\n\toverride suspend fun write(key: NovelTranslationSemanticKey, translatedText: String): Boolean = false\n}\n\ninternal class NovelTranslationCacheCorruptionException(\n\tmessage: String,\n\tcause: Throwable? = null,\n) : IOException(message, cause)\n\n/** App-private, bounded persistent segment-result cache. Source text and provider secrets are never stored. */""",
)
replace_exact(
    reliability,
    """\t\t} catch (_: IOException) {\n\t\t\tfile.delete()\n\t\t\tnull\n\t\t}\n""",
    """\t\t} catch (error: IOException) {\n\t\t\tfile.delete()\n\t\t\tthrow NovelTranslationCacheCorruptionException(\"Corrupt translation cache entry recovered\", error)\n\t\t}\n""",
)
replace_exact(
    reliability,
    """\tfun onSuccess(providerKey: String) = synchronized(providers) {\n\t\tproviders[providerKey] = ProviderState()\n\t}\n\n\tfun onFailure(providerKey: String, failure: NovelTranslationFailure) = synchronized(providers) {""",
    """\tfun onSuccess(providerKey: String) = synchronized(providers) {\n\t\tproviders[providerKey] = ProviderState()\n\t}\n\n\t/** Cancellation is neutral: release a half-open probe without counting success or failure. */\n\tfun onCancellation(providerKey: String) = synchronized(providers) {\n\t\tproviders[providerKey]?.halfOpenProbeInFlight = false\n\t}\n\n\tfun onFailure(providerKey: String, failure: NovelTranslationFailure) = synchronized(providers) {""",
)
replace_exact(
    reliability,
    """enum class NovelTranslationDiagnosticKind {\n\tCACHE_HIT,\n\tCACHE_MISS,\n\tCACHE_STORE,""",
    """enum class NovelTranslationDiagnosticKind {\n\tCACHE_HIT,\n\tCACHE_MISS,\n\tCACHE_CORRUPTION_RECOVERED,\n\tCACHE_READ_ERROR_RECOVERED,\n\tCACHE_STORE,""",
)

# 2) Coordinator: preserve source boundary whitespace, release cancelled half-open probes,
# and retain typed cache-recovery diagnostics.
replace_exact(coordinator, "safeCacheRead(key)", "safeCacheRead(key, provider, segments.size)", expected=2)
replace_exact(
    coordinator,
    """\t\t\t\treliability.circuitBreaker.onSuccess(provider.semanticIdentity.circuitKey)\n\t\t\t\treturn result\n\t\t\t} catch (error: CancellationException) {\n\t\t\t\tthrow error\n\t\t\t} catch (error: Throwable) {""",
    """\t\t\t\treliability.circuitBreaker.onSuccess(provider.semanticIdentity.circuitKey)\n\t\t\t\treturn preserveSegmentBoundaryWhitespace(segment.text, result)\n\t\t\t} catch (error: CancellationException) {\n\t\t\t\treliability.circuitBreaker.onCancellation(provider.semanticIdentity.circuitKey)\n\t\t\t\tthrow error\n\t\t\t} catch (error: Throwable) {""",
)
replace_exact(
    coordinator,
    """\tprivate suspend fun safeCacheRead(key: NovelTranslationSemanticKey): String? = try {\n\t\treliability.cache.read(key)\n\t} catch (error: CancellationException) {\n\t\tthrow error\n\t} catch (_: Exception) {\n\t\tnull\n\t}\n""",
    """\tprivate fun preserveSegmentBoundaryWhitespace(source: String, translated: String): String {\n\t\tval firstContent = source.indexOfFirst { !it.isWhitespace() }\n\t\tif (firstContent < 0) return source\n\t\tval lastContent = source.indexOfLast { !it.isWhitespace() }\n\t\treturn buildString(source.length.coerceAtLeast(translated.length)) {\n\t\t\tappend(source, 0, firstContent)\n\t\t\tappend(translated.trim())\n\t\t\tappend(source, lastContent + 1, source.length)\n\t\t}\n\t}\n\n\tprivate suspend fun safeCacheRead(\n\t\tkey: NovelTranslationSemanticKey,\n\t\tprovider: NovelTranslationProvider,\n\t\tsegmentCount: Int,\n\t): String? = try {\n\t\treliability.cache.read(key)\n\t} catch (error: CancellationException) {\n\t\tthrow error\n\t} catch (_: NovelTranslationCacheCorruptionException) {\n\t\trecordDiagnostic(\n\t\t\tkind = NovelTranslationDiagnosticKind.CACHE_CORRUPTION_RECOVERED,\n\t\t\tprovider = provider,\n\t\t\tkey = key,\n\t\t\terrorCategory = NovelTranslationFailureCategory.CACHE_CORRUPTION,\n\t\t\tsegmentCount = segmentCount,\n\t\t)\n\t\tnull\n\t} catch (_: Exception) {\n\t\trecordDiagnostic(\n\t\t\tkind = NovelTranslationDiagnosticKind.CACHE_READ_ERROR_RECOVERED,\n\t\t\tprovider = provider,\n\t\t\tkey = key,\n\t\t\terrorCategory = NovelTranslationFailureCategory.INTERNAL_RECOVERABLE,\n\t\t\tsegmentCount = segmentCount,\n\t\t)\n\t\tnull\n\t}\n""",
)
replace_exact(
    coordinator,
    """\t\tNovelTranslationFailureCategory.REQUEST_REJECTED,\n\t\tNovelTranslationFailureCategory.INVALID_RESPONSE,\n\t\tNovelTranslationFailureCategory.CIRCUIT_OPEN -> false\n""",
    """\t\tNovelTranslationFailureCategory.REQUEST_REJECTED,\n\t\tNovelTranslationFailureCategory.INVALID_RESPONSE,\n\t\tNovelTranslationFailureCategory.CACHE_CORRUPTION,\n\t\tNovelTranslationFailureCategory.INTERNAL_RECOVERABLE,\n\t\tNovelTranslationFailureCategory.CIRCUIT_OPEN -> false\n""",
)
replace_exact(
    coordinator,
    """\tINVALID_RESPONSE,\n\tCIRCUIT_OPEN,\n}""",
    """\tINVALID_RESPONSE,\n\tCACHE_CORRUPTION,\n\tINTERNAL_RECOVERABLE,\n\tCIRCUIT_OPEN,\n}""",
)
replace_exact(
    coordinator,
    """\t\terror: NovelTranslationFailure? = null,\n\t\tlatencyMillis: Long? = null,""",
    """\t\terror: NovelTranslationFailure? = null,\n\t\terrorCategory: NovelTranslationFailureCategory? = null,\n\t\tlatencyMillis: Long? = null,""",
)
replace_exact(
    coordinator,
    """\t\t\t\terrorCategory = error?.category,""",
    """\t\t\t\terrorCategory = errorCategory ?: error?.category,""",
)

# 3) Translated-only must retain the boundary whitespace produced by the coordinator.
replace_exact(
    projection,
    """\tprivate fun translatedText(item: NovelTranslationDisplaySegment): String = item.translatedText.trim()\n\tprivate fun bilingualBlock(item: NovelTranslationDisplaySegment): String = \"\\n${translatedText(item)}\\n\"""",
    """\tprivate fun translatedText(item: NovelTranslationDisplaySegment): String = item.translatedText\n\tprivate fun bilingualBlock(item: NovelTranslationDisplaySegment): String = \"\\n${item.translatedText.trim()}\\n\"""",
)
replace_exact(
    reader,
    "output.replace(start, end, item.translatedText.trim())",
    "output.replace(start, end, item.translatedText)",
)

# 4) Reliability tests: corruption diagnostics and half-open cancellation recovery.
replace_exact(
    reliability_test,
    """\t\tentry.writeText(\"corrupt\")\n\t\tassertNull(NovelTranslationSemanticCache(directory).read(key))\n\t\tassertFalse(entry.exists())\n""",
    """\t\tentry.writeText(\"corrupt\")\n\t\ttry {\n\t\t\tNovelTranslationSemanticCache(directory).read(key)\n\t\t\tfail(\"Expected cache corruption signal\")\n\t\t} catch (_: NovelTranslationCacheCorruptionException) {\n\t\t\t// Corruption is surfaced internally so the coordinator can record a typed recovery event.\n\t\t}\n\t\tassertFalse(entry.exists())\n""",
)
replace_exact(
    reliability_test,
    """\tprivate fun assertCircuitRejected(breaker: NovelTranslationCircuitBreaker, provider: String) {""",
    """\t@Test\n\tfun `corrupt cache is recovered as miss with typed diagnostic`() = runBlocking {\n\t\tval directory = temporary.newFolder()\n\t\tval diagnostics = NovelTranslationDiagnostics()\n\t\tval cache = NovelTranslationSemanticCache(directory)\n\t\tval reliability = newServices(cache = cache, diagnostics = diagnostics)\n\t\tcoordinator(\n\t\t\tengine = engine { \"first\" },\n\t\t\treliabilityServices = reliability,\n\t\t\tmaxAttempts = 1,\n\t\t).translate(operation(\"source\"))\n\t\tval entry = directory.listFiles().orEmpty().single { it.name.endsWith(\".translation\") }\n\t\tentry.writeText(\"corrupt\")\n\t\tdiagnostics.clear()\n\t\tvar calls = 0\n\n\t\tval result = coordinator(\n\t\t\tengine = engine { calls++; \"fresh\" },\n\t\t\treliabilityServices = reliability,\n\t\t\tmaxAttempts = 1,\n\t\t).translate(operation(\"source\"))\n\n\t\tassertEquals(\"fresh\", result.translatedText)\n\t\tassertEquals(1, calls)\n\t\tassertTrue(diagnostics.snapshot().any { event ->\n\t\t\tevent.kind == NovelTranslationDiagnosticKind.CACHE_CORRUPTION_RECOVERED &&\n\t\t\t\tevent.errorCategory == NovelTranslationFailureCategory.CACHE_CORRUPTION\n\t\t})\n\t}\n\n\t@Test\n\tfun `cancelled half open probe releases provider for another probe`() {\n\t\tvar now = 1_000L\n\t\tval breaker = NovelTranslationCircuitBreaker(failureThreshold = 1, cooldownMillis = 100L, nowMillis = { now })\n\t\tval transient = NovelTranslationFailure(NovelTranslationFailureCategory.PROVIDER_UNAVAILABLE, message = \"temporary\")\n\n\t\tassertEquals(NovelTranslationCircuitState.CLOSED, breaker.beforeRequest(\"provider\"))\n\t\tbreaker.onFailure(\"provider\", transient)\n\t\tassertEquals(NovelTranslationCircuitState.OPEN, breaker.state(\"provider\"))\n\t\tnow += 100L\n\t\tassertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.beforeRequest(\"provider\"))\n\t\tbreaker.onCancellation(\"provider\")\n\t\tassertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.state(\"provider\"))\n\t\tassertEquals(NovelTranslationCircuitState.HALF_OPEN, breaker.beforeRequest(\"provider\"))\n\t}\n\n\tprivate fun assertCircuitRejected(breaker: NovelTranslationCircuitBreaker, provider: String) {""",
)

# 5) Coordinator tests: chunk whitespace + scheduler queue cancellation/fairness/provider lanes.
replace_exact(
    coordinator_test,
    """import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.async""",
    """import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CompletableDeferred\nimport kotlinx.coroutines.async""",
)
replace_exact(
    coordinator_test,
    """import kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.test.runTest""",
    """import kotlinx.coroutines.cancelAndJoin\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.test.runTest""",
)
replace_exact(
    coordinator_test,
    """import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull""",
    """import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNull""",
)
replace_exact(
    coordinator_test,
    """\tprivate fun coordinator(\n""",
    """\t@Test\n\tfun `translated chunks preserve source paragraph whitespace`() = runTest {\n\t\tval text = \"a\".repeat(254) + \"\\n\\n\" + \"b\".repeat(256)\n\t\tval coordinator = coordinator(\n\t\t\tengine = engine { request -> if (request.text.startsWith('a')) \"ONE\" else \"TWO\" },\n\t\t)\n\n\t\tval result = coordinator.translate(operation(text))\n\n\t\tassertEquals(\"ONE\\n\\nTWO\", result.translatedText)\n\t}\n\n\t@Test\n\tfun `scheduler cancellation removes queued work cleanly`() = runTest {\n\t\tval scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})\n\t\tval capabilities = capabilities(maxConcurrency = 1)\n\t\tval started = CompletableDeferred<Unit>()\n\t\tval release = CompletableDeferred<Unit>()\n\t\tvar queuedExecuted = false\n\t\tval first = async {\n\t\t\tscheduler.execute(capabilities) {\n\t\t\t\tstarted.complete(Unit)\n\t\t\t\trelease.await()\n\t\t\t}\n\t\t}\n\t\tstarted.await()\n\t\tval queued = async { scheduler.execute(capabilities) { queuedExecuted = true } }\n\t\tyield()\n\t\tqueued.cancelAndJoin()\n\t\trelease.complete(Unit)\n\t\tfirst.await()\n\t\tscheduler.execute(capabilities) { Unit }\n\n\t\tassertFalse(queuedExecuted)\n\t}\n\n\t@Test\n\tfun `scheduler lets queued small work run before large work reacquires`() = runTest {\n\t\tval scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})\n\t\tval capabilities = capabilities(maxConcurrency = 1)\n\t\tval firstStarted = CompletableDeferred<Unit>()\n\t\tval releaseFirst = CompletableDeferred<Unit>()\n\t\tval order = mutableListOf<String>()\n\t\tval large = async {\n\t\t\tscheduler.execute(capabilities) {\n\t\t\t\torder += \"large-1\"\n\t\t\t\tfirstStarted.complete(Unit)\n\t\t\t\treleaseFirst.await()\n\t\t\t}\n\t\t\tscheduler.execute(capabilities) { order += \"large-2\" }\n\t\t}\n\t\tfirstStarted.await()\n\t\tval small = async { scheduler.execute(capabilities) { order += \"small\" } }\n\t\tyield()\n\t\treleaseFirst.complete(Unit)\n\t\tlarge.await()\n\t\tsmall.await()\n\n\t\tassertEquals(listOf(\"large-1\", \"small\", \"large-2\"), order)\n\t}\n\n\t@Test\n\tfun `scheduler isolates provider lanes`() = runTest {\n\t\tval scheduler = NovelTranslationRequestScheduler(nowMillis = { 0L }, sleep = {})\n\t\tval providerA = capabilities(maxConcurrency = 1).copy(providerId = \"provider-a\")\n\t\tval providerB = capabilities(maxConcurrency = 1).copy(providerId = \"provider-b\")\n\t\tval aStarted = CompletableDeferred<Unit>()\n\t\tval releaseA = CompletableDeferred<Unit>()\n\t\tvar secondARan = false\n\t\tvar bRan = false\n\t\tval firstA = async {\n\t\t\tscheduler.execute(providerA) {\n\t\t\t\taStarted.complete(Unit)\n\t\t\t\treleaseA.await()\n\t\t\t}\n\t\t}\n\t\taStarted.await()\n\t\tval secondA = async { scheduler.execute(providerA) { secondARan = true } }\n\t\tval callB = async { scheduler.execute(providerB) { bRan = true } }\n\t\tcallB.await()\n\n\t\tassertTrue(bRan)\n\t\tassertFalse(secondARan)\n\t\treleaseA.complete(Unit)\n\t\tfirstA.await()\n\t\tsecondA.await()\n\t\tassertTrue(secondARan)\n\t}\n\n\tprivate fun coordinator(\n""",
)

# 6) Projection regression for adjacent chunk boundaries.
replace_exact(
    projection_test,
    """\t@Test\n\tfun `invalid or overlapping display segments cannot escape source bounds`() {""",
    """\t@Test\n\tfun `translated only preserves whitespace between adjacent translated chunks`() {\n\t\tval source = \"alpha\\n\\nbeta\"\n\t\tval chunks = listOf(\n\t\t\tNovelTranslationDisplaySegment(0, 7, \"satu\\n\\n\"),\n\t\t\tNovelTranslationDisplaySegment(7, 11, \"dua\"),\n\t\t)\n\n\t\tassertEquals(\n\t\t\t\"satu\\n\\ndua\",\n\t\t\tNovelTranslationProjection.displayText(source, chunks, NovelTranslationDisplayMode.TRANSLATED_ONLY),\n\t\t)\n\t}\n\n\t@Test\n\tfun `invalid or overlapping display segments cannot escape source bounds`() {""",
)

print("Novel translation final audit patch applied successfully")
