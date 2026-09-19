package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetailsChapterRoomFlowRegressionTest {

	@Test
	fun `chapters dao exposes a manga scoped Room Flow`() {
		val dao = source("org/koitharu/kotatsu/core/db/dao/ChaptersDao.kt")

		assertTrue(
			dao.contains(
				"@Query(\"SELECT*FROMchaptersWHEREmanga_id=:mangaIdORDERBY`index`ASC\")" +
					"abstractfunobserveAll(mangaId:Long):Flow<List<ChapterEntity>>",
			),
		)
		assertFalse(dao.contains("ChapterRevision"))
		assertFalse(dao.contains("mangaRevisions"))
		assertFalse(dao.contains("globalRevision"))
		assertFalse(dao.contains("funrevision("))
	}

	@Test
	fun `repository suppresses unchanged results from unrelated chapter table writes`() {
		val repository = source("org/koitharu/kotatsu/core/parser/MangaDataRepository.kt")
		val observer = repository
			.substringAfter("funobserveChapters(mangaId:Long):Flow<List<MangaChapter>>{")
			.substringBefore("suspendfunfindMangaById(")

		assertTrue(observer.contains("db.getChaptersDao().observeAll(mangaId)"))
		assertTrue(observer.contains(".map{it.toMangaChapters()}"))
		assertTrue(observer.contains(".distinctUntilChanged()"))
	}

	@Test
	fun `Details consumes per manga Flow and no longer owns table invalidation bookkeeping`() {
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")

		assertTrue(details.contains("mangaDataRepository.observeChapters(mangaId)"))
		assertTrue(
			"Initial Room state is already read by DetailsLoadUseCase and must not be materialized twice",
			details.contains(".drop(1)"),
		)
		assertTrue(details.contains(".mapLatest{chapters->syncCachedChaptersWhenLoadIdle(chapters)}"))

		for (forbidden in listOf(
			"TABLE_CHAPTERS",
			"ChapterRevision",
			"cachedChapterRevision",
			"invalidationTracker.createFlow(",
			".revision(mangaId)",
			"getChaptersDao().findAll(mangaId)",
		)) {
			assertFalse("Details must not keep manual chapter invalidation primitive: $forbidden", details.contains(forbidden))
		}
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
