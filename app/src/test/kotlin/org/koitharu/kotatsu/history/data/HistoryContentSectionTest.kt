package org.koitharu.kotatsu.history.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaNovelSection

class HistoryContentSectionTest {

	@Test fun `section restores safely and partitions type flags`() {
		assertEquals(MangaNovelSection.MANGA, MangaNovelSection.fromSaved(null))
		assertEquals(MangaNovelSection.MANGA, MangaNovelSection.fromSaved("unknown"))
		assertEquals(MangaNovelSection.NOVEL, MangaNovelSection.fromSaved("NOVEL"))
		assertTrue(MangaNovelSection.NOVEL.includes(true))
		assertFalse(MangaNovelSection.NOVEL.includes(false))
		assertTrue(MangaNovelSection.MANGA.includes(false))
		assertFalse(MangaNovelSection.MANGA.includes(true))
	}

	@Test fun `novel predicate quotes dynamic source names and handles local epub`() {
		val sql = historyContentSectionPredicate(MangaNovelSection.NOVEL, listOf("LN_sample", "LN_some'quote"))
		assertTrue(sql.contains("manga.source IN ('LN_sample','LN_some''quote')"))
		assertTrue(sql.contains("manga.source = 'LOCAL'"))
		assertTrue(sql.contains("LIKE '%.epub'"))
		assertTrue(sql.contains("LIKE '%/00.novel/%'"))
	}

	@Test fun `manga is logical complement of exactly the same persisted novel condition`() {
		val novel = historyContentSectionPredicate(MangaNovelSection.NOVEL, listOf("LN_test"))
		val manga = historyContentSectionPredicate(MangaNovelSection.MANGA, listOf("LN_test"))
		assertEquals("NOT " + novel, manga)
	}

	@Test fun `history query applies partition before SQL limit across normal private and downloaded`() {
		val dao = source("history/data/HistoryDao.kt")
		val repository = source("history/data/HistoryRepository.kt")
		val local = source("history/data/HistoryLocalObserver.kt")
		assertTrue(dao.contains(".apply { if (contentPredicate != null) where(contentPredicate) }"))
		assertTrue(dao.contains(".limit(limit)"))
		assertTrue(repository.contains("localObserver.observeAll(order, filterOptions, limit, minUpdatedAt, predicate)"))
		assertTrue(repository.contains("observeAllPrivate(order, filterOptions, limit, minUpdatedAt, predicate)"))
		assertTrue(local.contains("observeAll(order, filterOptions, limit, minUpdatedAt, contentPredicate)"))
	}
	private fun source(file: String): String =
		sequenceOf(
			java.io.File("src/main/kotlin/org/koitharu/kotatsu", file),
			java.io.File("app/src/main/kotlin/org/koitharu/kotatsu", file),
		).first(java.io.File::isFile).readText()
}
