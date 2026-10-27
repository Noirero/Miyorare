package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MiyorareImageDiskCacheTest {

	private lateinit var context: Context
	private var cache: MiyorareImageDiskCache? = null

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		deleteTestStorage()
	}

	@After
	fun tearDown() {
		cache?.shutdown()
		cache = null
		deleteTestStorage()
	}

	@Test
	fun coverSurvivesAndroidCacheDirectoryRemoval() {
		val first = MiyorareImageDiskCache(context).also { cache = it }
		write(first, COVER_KEY, "cover")
		write(first, VOLATILE_KEY, "temporary")
		first.shutdown()
		cache = null

		volatileRoot().deleteRecursively()

		val reopened = MiyorareImageDiskCache(context).also { cache = it }
		assertNotNull(reopened.openSnapshot(COVER_KEY)?.also { it.close() })
		assertNull(reopened.openSnapshot(VOLATILE_KEY))
	}

	@Test
	fun smartLocalCoverUsesSamePersistentIdentityAcrossReopen() {
		val first = MiyorareImageDiskCache(context).also { cache = it }
		write(first, SMART_LOCAL_COVER_KEY, "smart-local-cover")
		assertTrue(first.coverSize > 0L)
		assertNotNull(first.openSnapshot(COVER_KEY)?.also { it.close() })
		first.shutdown()
		cache = null

		// Simulate the disposable Android cache disappearing between processes. A Smart Local cover
		// must still be found through either the virtual URL or the canonical cover:<id> identity.
		volatileRoot().deleteRecursively()
		val reopened = MiyorareImageDiskCache(context).also { cache = it }
		assertNotNull(reopened.openSnapshot(SMART_LOCAL_COVER_KEY)?.also { it.close() })
		assertNotNull(reopened.openSnapshot(COVER_KEY)?.also { it.close() })
	}

	@Test
	fun compactCollectionCoverSurvivesVolatileCacheRemovalWithoutRefetch() = runBlocking {
		val fetches = AtomicInteger()
		val sourceBitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply {
			eraseColor(Color.RED)
		}
		val fetcher = Fetcher.Factory<String> { data, _, _ ->
			if (data != REMOTE_COVER_URL) return@Factory null
			Fetcher {
				fetches.incrementAndGet()
				ImageFetchResult(sourceBitmap.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
			}
		}
		val store = StoredMangaCoverStore(storedRoot(), maxBytes = 4L * 1024 * 1024, maxEntries = 8)

		fun request() = ImageRequest.Builder(context)
			.data(REMOTE_COVER_URL)
			.diskCacheKey(COVER_KEY)
			.fetcherFactory(fetcher)
			.size(128, 192)
			.allowHardware(false)
			.apply {
				extras[storedMangaCoverRequestKey] = StoredMangaCoverRequest(
					mangaId = 123L,
					sourceUrl = REMOTE_COVER_URL,
					persist = true,
					forceRefresh = false,
				)
			}
			.build()

		fun loader(): ImageLoader {
			val volatile = DiskCache.Builder().directory(volatileRoot()).build()
			val routed = MiyorareImageDiskCache(context, volatile)
			val pipeline = StoredMangaCoverPipeline(store, routed)
			return ImageLoader.Builder(context)
				.diskCache { routed }
				.components { add(Interceptor { chain -> pipeline.proceed(chain) }) }
				.build()
		}

		try {
			val first = loader()
			try {
				assertTrue(first.execute(request()) is SuccessResult)
			} finally {
				first.shutdown()
			}
			assertEquals(1, fetches.get())
			assertTrue(store.size() > 0L)

			// Android's disposable image cache can disappear completely. The second process-like loader
			// must resolve the compact app-Data file and never call the remote fetcher again.
			volatileRoot().deleteRecursively()
			val second = loader()
			try {
				assertTrue(second.execute(request()) is SuccessResult)
			} finally {
				second.shutdown()
			}
			assertEquals(1, fetches.get())
		} finally {
			sourceBitmap.recycle()
		}
	}

	@Test
	fun legacyStableCoverIsMigratedWithoutFilenameGuessing() {
		val legacy = DiskCache.Builder().directory(volatileRoot()).build()
		write(legacy, COVER_KEY, "legacy-cover")
		legacy.shutdown()

		val routed = MiyorareImageDiskCache(context).also { cache = it }
		assertNotNull(routed.openSnapshot(COVER_KEY)?.also { it.close() })
		assertTrue(routed.coverSize > 0L)
		routed.shutdown()
		cache = null
		volatileRoot().deleteRecursively()

		val reopened = MiyorareImageDiskCache(context).also { cache = it }
		assertNotNull(reopened.openSnapshot(COVER_KEY)?.also { it.close() })
	}

	@Test
	fun explicitCoverClearPreventsLegacyCoverResurrection() {
		val legacy = DiskCache.Builder().directory(volatileRoot()).build()
		write(legacy, COVER_KEY, "legacy-cover")
		write(legacy, VOLATILE_KEY, "temporary")
		legacy.shutdown()

		val routed = MiyorareImageDiskCache(context).also { cache = it }
		routed.clearCovers()
		routed.shutdown()
		cache = null

		val reopened = MiyorareImageDiskCache(context).also { cache = it }
		assertNull(reopened.openSnapshot(COVER_KEY))
		assertNotNull(reopened.openSnapshot(VOLATILE_KEY)?.also { it.close() })
		assertTrue(reopened.coverSize == 0L)
	}

	@Test
	fun scopedClearDoesNotCrossStorageOwnership() {
		val routed = MiyorareImageDiskCache(context).also { cache = it }
		write(routed, COVER_KEY, "cover")
		write(routed, VOLATILE_KEY, "temporary")

		routed.clearVolatile()
		assertNotNull(routed.openSnapshot(COVER_KEY)?.also { it.close() })
		assertNull(routed.openSnapshot(VOLATILE_KEY))

		write(routed, VOLATILE_KEY, "temporary")
		routed.clearCovers()
		assertNull(routed.openSnapshot(COVER_KEY))
		assertNotNull(routed.openSnapshot(VOLATILE_KEY)?.also { it.close() })
	}

	@Test
	fun coverSizeTracksOnlyPersistentCoverEntries() {
		val routed = MiyorareImageDiskCache(context).also { cache = it }
		write(routed, VOLATILE_KEY, "temporary")
		val beforeCover = routed.coverSize
		write(routed, COVER_KEY, "cover-payload")

		assertTrue(routed.coverSize > beforeCover)
		routed.clearCovers()
		assertTrue(routed.coverSize <= beforeCover)
		assertNotNull(routed.openSnapshot(VOLATILE_KEY)?.also { it.close() })
	}

	private fun write(cache: DiskCache, key: String, value: String) {
		val editor = checkNotNull(cache.openEditor(key))
		cache.fileSystem.write(editor.metadata) { writeUtf8("metadata") }
		cache.fileSystem.write(editor.data) { writeUtf8(value) }
		editor.commit()
	}

	private fun deleteTestStorage() {
		volatileRoot().deleteRecursively()
		coverRoot().deleteRecursively()
		storedRoot().deleteRecursively()
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
	}

	private fun volatileRoot(): File = (context.externalCacheDir ?: context.cacheDir).resolve("image_cache")

	private fun coverRoot(): File = (context.getExternalFilesDir("covers") ?: File(context.filesDir, "covers"))

	private fun storedRoot(): File = File(context.filesDir, "stored-cover-test")

	private companion object {
		const val COVER_KEY = "cover:123"
		const val SMART_LOCAL_COVER_KEY = "smart-local://cover/123"
		const val VOLATILE_KEY = "https://example.test/not-a-cover.jpg"
		const val REMOTE_COVER_URL = "https://example.test/library-cover.png"
		const val PREFS_NAME = "persistent_cover_cache"
	}
}
