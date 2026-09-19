package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionDetailsStage1RegressionTest {

	@Test
	fun `routine chapter gc retains recent initialized Details snapshots`() {
		val dao = source("org/koitharu/kotatsu/core/db/dao/ChaptersDao.kt")

		assertTrue(dao.contains("details_updated_at>=:recentDetailsCutoff"))
		assertTrue(dao.contains("chapters_initialized=1"))
		assertTrue(dao.contains("DetailsCachePolicy.recentDetailsCutoff()"))
		assertTrue(dao.contains("gc(mangaIds,DetailsCachePolicy.recentDetailsCutoff())"))
	}

	@Test
	fun `explicit clear manga data bypasses recent Details retention`() {
		val repository = source("org/koitharu/kotatsu/core/parser/MangaDataRepository.kt")
		val cleanup = repository
			.substringAfter("suspendfuncleanupDatabase(){")
			.substringBefore("funobserveOverridesTrigger")

		assertTrue(cleanup.contains("db.getChaptersDao().gc(Long.MAX_VALUE)"))
		assertTrue(cleanup.contains("db.getMangaDao().cleanup(idsFromShortcuts)"))
		assertFalse(cleanup.contains("recentDetailsCutoff"))
	}

	@Test
	fun `single and batch history removals do not sweep unrelated Extension cache`() {
		val history = source("org/koitharu/kotatsu/history/data/HistoryRepository.kt")

		assertTrue(history.contains("mangaRepository.gcChaptersCache(setOf(manga.id))"))
		assertTrue(history.contains("mangaRepository.gcChaptersCache(ids)"))
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
