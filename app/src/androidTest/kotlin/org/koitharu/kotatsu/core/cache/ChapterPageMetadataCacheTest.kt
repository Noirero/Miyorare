package org.koitharu.kotatsu.core.cache

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.util.concurrent.TimeUnit

class ChapterPageMetadataCacheTest {

	private lateinit var application: Application
	private lateinit var cache: ChapterPageMetadataCache
	private val source = MangaSource("TEST_PAGE_METADATA")
	private val chapter = MangaChapter(
		id = 101L,
		title = "Chapter 1",
		number = 1f,
		volume = 1,
		url = "/chapter/1",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = source,
	)
	private val pages = listOf(
		MangaPage(id = 1L, url = "https://example.test/1.jpg", preview = null, source = source),
		MangaPage(id = 2L, url = "https://example.test/2.jpg", preview = "https://example.test/p2.jpg", source = source),
	)

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		cacheDirectory().deleteRecursively()
		cache = ChapterPageMetadataCache(application)
	}

	@After
	fun tearDown() {
		cacheDirectory().deleteRecursively()
	}

	@Test
	fun missingEntryIsCacheMiss() = runTest {
		assertNull(cache.get(source, chapter, sourceVersion = "v1", now = 1_000L))
	}

	@Test
	fun roundTripPreservesPageList() = runTest {
		cache.put(source, chapter, pages, sourceVersion = "v1", now = 1_000L)

		assertEquals(pages, cache.get(source, chapter, sourceVersion = "v1", now = 2_000L))
	}

	@Test
	fun expiredEntryIsCacheMiss() = runTest {
		val cachedAt = 1_000L
		cache.put(source, chapter, pages, sourceVersion = "v1", now = cachedAt)

		assertNull(
			cache.get(
				source,
				chapter,
				sourceVersion = "v1",
				now = cachedAt + TimeUnit.HOURS.toMillis(48) + 1L,
			),
		)
	}

	@Test
	fun sourceVersionChangeIsCacheMiss() = runTest {
		cache.put(source, chapter, pages, sourceVersion = "v1", now = 1_000L)

		assertNull(cache.get(source, chapter, sourceVersion = "v2", now = 2_000L))
	}

	@Test
	fun corruptEntryIsDeletedAndTreatedAsMiss() = runTest {
		cache.put(source, chapter, pages, sourceVersion = "v1", now = 1_000L)
		val entry = requireNotNull(cacheDirectory().listFiles()?.singleOrNull())
		entry.writeText("not-json")

		assertNull(cache.get(source, chapter, sourceVersion = "v1", now = 2_000L))
		assertEquals(false, entry.exists())
	}

	@Test
	fun explicitInvalidationRemovesEntry() = runTest {
		cache.put(source, chapter, pages, sourceVersion = "v1", now = 1_000L)
		cache.invalidate(source, chapter, sourceVersion = "v1")

		assertNull(cache.get(source, chapter, sourceVersion = "v1", now = 2_000L))
	}

	private fun cacheDirectory() = File(application.cacheDir, "chapter_page_metadata")
}
