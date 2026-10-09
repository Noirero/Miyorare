package org.koitharu.kotatsu.core.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.disk.DiskCache
import coil3.disk.directory
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
	}

	private fun volatileRoot(): File = (context.externalCacheDir ?: context.cacheDir).resolve("image_cache")

	private fun coverRoot(): File = (context.getExternalFilesDir("covers") ?: File(context.filesDir, "covers"))

	private companion object {
		const val COVER_KEY = "cover:123"
		const val SMART_LOCAL_COVER_KEY = "smart-local://cover/123"
		const val VOLATILE_KEY = "https://example.test/not-a-cover.jpg"
		const val PREFS_NAME = "persistent_cover_cache"
	}
}
