package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.details.data.ChapterPersonalRepository
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

/** Checks the compiled ownership contract rather than matching source text. */
class ChapterPersonalOwnershipTest {
	@Test
	fun readerAndDetailsRequireTheSamePersonalRepositoryDependency() {
		for (owner in listOf(ReaderViewModel::class.java, DetailsViewModel::class.java, ChaptersPagesViewModel::class.java)) {
			assertTrue("${owner.simpleName} must consume chapter personal state", owner.declaredConstructors.any { constructor ->
				ChapterPersonalRepository::class.java in constructor.parameterTypes
			})
		}
	}

	@Test
	fun readerAndDetailsInheritOneEditorOwnerAndSavePath() {
		for (owner in listOf(ReaderViewModel::class.java, DetailsViewModel::class.java)) {
			for (method in listOf(
				owner.getMethod("editChapterPersonal", ChapterListItem::class.java),
				owner.getMethod("dismissChapterPersonalEditor"),
				owner.getMethod("saveChapterPersonal", Int::class.javaObjectType, String::class.java),
				owner.getMethod("getChapterPersonalEditor"),
				owner.getMethod("isSavingChapterPersonal"),
			)) {
				assertEquals("${owner.simpleName}.${method.name} must use shared ownership", ChaptersPagesViewModel::class.java, method.declaringClass)
			}
		}
	}
}
