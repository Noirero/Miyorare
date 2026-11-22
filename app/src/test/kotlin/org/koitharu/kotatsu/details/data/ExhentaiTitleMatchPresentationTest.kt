package org.koitharu.kotatsu.details.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File

/** Presentation keeps only explicit transient resolver provenance across Details snapshots. */
class ExhentaiTitleMatchPresentationTest {
	@Test fun titleOnlyStatusSurvivesSnapshotRefreshAndChapterFilters() {
		val remote = SampleData.mangaDetails
		val local = LocalManga(remote, File("chapter.cbz"), matchedByTitle = true)
		val details = MangaDetails(remote).copy(localManga = local)
		assertTrue(details.local!!.matchedByTitle)
		assertTrue(details.copy(isLoaded = true).local!!.matchedByTitle)
		assertTrue(details.withMergedBranches().local!!.matchedByTitle)
		assertTrue(details.filterChapters(null).local!!.matchedByTitle)
	}

	@Test fun genericLocalMangaIsNotAutomaticallyLabeledTitleOnly() {
		val remote = SampleData.mangaDetails
		val details = MangaDetails(remote).copy(localManga = LocalManga(remote, File("chapter.cbz")))
		assertFalse(details.local!!.matchedByTitle)
		assertFalse(details.copy(isLoaded = true).local!!.matchedByTitle)
	}
}
