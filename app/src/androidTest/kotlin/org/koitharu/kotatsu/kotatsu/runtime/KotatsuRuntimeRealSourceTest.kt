package org.koitharu.kotatsu.kotatsu.runtime

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.kotatsu.KotatsuMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Production-pack smoke test for one genuine parser from the exact runtime revision being tested.
 *
 * DANKE is deliberately used as the canary because its upstream parser is a small, public Guya API
 * implementation without login or Cloudflare bootstrap. This test crosses the real host boundary:
 * signed APK -> verifier -> store -> child-first DexClassLoader -> runtime entrypoint -> upstream
 * parser -> host MangaRepository -> details -> chapters -> pages -> the Reader's real PageLoader.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class KotatsuRuntimeRealSourceTest {

	@get:Rule var hiltRule = HiltAndroidRule(this)

	@Inject lateinit var runtimeManager: KotatsuRuntimeManager
	@Inject lateinit var repositories: MangaRepository.Factory

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val targetContext get() = instrumentation.targetContext
	private val runtimeRoot get() = File(targetContext.noBackupFilesDir, "kotatsu-runtime")

	@Before
	fun setUp() {
		hiltRule.inject()
		runtimeRoot.deleteRecursively()
		targetContext.getSharedPreferences("kotatsu_runtime_sources", android.content.Context.MODE_PRIVATE)
			.edit().clear().commit()
	}

	@After
	fun tearDown() {
		runtimeRoot.deleteRecursively()
		targetContext.getSharedPreferences("kotatsu_runtime_sources", android.content.Context.MODE_PRIVATE)
			.edit().clear().commit()
	}

	@Test
	fun dankeParserCrossesProductionRuntimeBoundaryToReaderPage() = runBlocking {
		val arguments = InstrumentationRegistry.getArguments()
		val runtimeApkPath = arguments.getString(ARG_RUNTIME_PACK_APK)
			requireNotNull(runtimeApkPath) { "Missing instrumentation argument: $ARG_RUNTIME_PACK_APK" }
		val upstreamRevision = arguments.getString(ARG_RUNTIME_UPSTREAM_REVISION)
			requireNotNull(upstreamRevision) { "Missing instrumentation argument: $ARG_RUNTIME_UPSTREAM_REVISION" }
		require(upstreamRevision.matches(Regex("[0-9a-f]{40}"))) { "Invalid runtime upstream revision: $upstreamRevision" }
		val expectedSourceCount = arguments.getString(ARG_RUNTIME_EXPECTED_SOURCE_COUNT)?.let { value ->
			value.toIntOrNull()?.takeIf { it > 0 }
				?: error("Invalid runtime expected source count: $value")
		}
		val runtimeApk = File(runtimeApkPath)
		assertTrue("The separately installed production runtime APK must be readable", runtimeApk.isFile)

		runtimeManager.install(
			runtimeApk,
			KotatsuRuntimePackManifest(
				protocolVersion = KotatsuRuntimePackContract.PROTOCOL_VERSION,
				packVersion = "android-real-source-smoke",
				upstreamRevision = upstreamRevision,
				sourceCount = expectedSourceCount ?: 0,
				sha256 = sha256(runtimeApk),
				downloadUrl = "https://example.invalid/kotatsu-runtime-real-source.apk",
			),
		)

		val availableSources = runtimeManager.getAvailableSources()
		if (expectedSourceCount != null) {
			assertEquals(
				"Production runtime catalog must match the parser catalog generated from the exact upstream revision",
				expectedSourceCount,
				availableSources.size,
			)
		}
		val source = availableSources.firstOrNull { it.sourceKey == CANARY_SOURCE }
			requireNotNull(source) { "Runtime catalog does not contain $CANARY_SOURCE" }
		assertFalse("The E2E canary must not be marked broken", source.isBroken)
		assertEquals("KOTATSU:$CANARY_SOURCE", source.name)

		runtimeManager.setSourceEnabled(source.sourceKey, true)
		assertTrue(runtimeManager.getEnabledSources().any { it.name == source.name })

		val repository = repositories.create(source)
		assertTrue("Factory must route KOTATSU:* through KotatsuMangaRepository", repository is KotatsuMangaRepository)

		val listing = withTimeout(NETWORK_TIMEOUT_MS) {
			repository.getList(0, repository.defaultSortOrder, null)
		}
		assertTrue("DANKE catalog must return at least one manga", listing.isNotEmpty())

		val seed = listing.first()
		val query = seed.title.take(12).trim()
		val search = withTimeout(NETWORK_TIMEOUT_MS) {
			repository.getList(0, repository.defaultSortOrder, MangaListFilter(query = query))
		}
		assertTrue("DANKE search must find the listed seed", search.any { it.id == seed.id })

		val details = withTimeout(NETWORK_TIMEOUT_MS) { repository.getDetails(seed) }
		val chapters = details.chapters.orEmpty()
		assertTrue("DANKE details must expose chapters", chapters.isNotEmpty())
		assertEquals(source.name, details.source.name)

		val pages = withTimeout(NETWORK_TIMEOUT_MS) { repository.getPages(chapters.first()) }
		assertTrue("DANKE chapter must expose Reader pages", pages.isNotEmpty())
		assertTrue(pages.all { it.source.name == source.name })

		val pageUrl = withTimeout(NETWORK_TIMEOUT_MS) { repository.getPageUrl(pages.first()) }
		assertTrue("Reader page URL must be an absolute HTTPS URL", pageUrl.startsWith("https://"))

		// Cross the actual Reader data path as well: PageLoader resolves the page's KOTATSU source
		// through MangaRepository.Factory, fetches the full page and materializes it into the reader
		// cache. A Hilt activity is only used to obtain the real ActivityRetained-scoped PageLoader.
		ActivityScenario.launch(MangaUpdatesAuthActivity::class.java).use { scenario ->
			lateinit var pageLoader: PageLoader
			scenario.onActivity { activity ->
				pageLoader = EntryPointAccessors.fromActivity(
					activity,
					ReaderPageLoaderTestEntryPoint::class.java,
				).pageLoader()
			}
			val readerPageUri = withTimeout(NETWORK_TIMEOUT_MS) {
				pageLoader.loadPage(pages.first(), force = false)
			}
			assertNotNull("Reader PageLoader must materialize a readable page URI", readerPageUri.scheme)
			assertTrue("Reader PageLoader must return a non-empty page URI", readerPageUri.toString().isNotBlank())
		}
	}

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count <= 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private companion object {
		const val ARG_RUNTIME_PACK_APK = "runtimePackApk"
		const val ARG_RUNTIME_UPSTREAM_REVISION = "runtimeUpstreamRevision"
		const val ARG_RUNTIME_EXPECTED_SOURCE_COUNT = "runtimeExpectedSourceCount"
		const val CANARY_SOURCE = "DANKE"
		const val NETWORK_TIMEOUT_MS = 90_000L
	}
}

@EntryPoint
@InstallIn(ActivityComponent::class)
internal interface ReaderPageLoaderTestEntryPoint {
	fun pageLoader(): PageLoader
}
