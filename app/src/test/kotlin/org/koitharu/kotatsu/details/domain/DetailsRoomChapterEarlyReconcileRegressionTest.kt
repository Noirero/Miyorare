package org.koitharu.kotatsu.details.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsRoomChapterEarlyReconcileRegressionTest {

	@Test
	fun `non empty Room chapters can fill an empty source slot during active load`() {
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")
		val sync = details
			.substringAfter("private suspend fun syncCachedChaptersWhenLoadIdle")
			.substringBefore("private fun doLoad")

		assertTrue(sync.contains("canPromoteDuringLoad=observedLoad.isActive&&chapters.isNotEmpty()&&currentSourceChapters.isEmpty()"))
		assertTrue(sync.contains("if(observedLoad.isActive&&!canPromoteDuringLoad){observedLoad.join()"))
		assertFalse(sync.contains("if(observedLoad.isActive){observedLoad.join()"))
	}

	@Test
	fun `early reconcile keeps local empty and concurrent state guards`() {
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")
		val sync = details
			.substringAfter("private suspend fun syncCachedChaptersWhenLoadIdle")
			.substringBefore("private fun doLoad")

		assertTrue(sync.contains("if(current.isLocal)return"))
		assertTrue(sync.contains("if(chapters.isEmpty()&&currentSourceChapters.isNotEmpty())return"))
		assertTrue(sync.contains("if(loadingJob!==observedLoad||mangaDetails.value!==current)continue"))
		assertTrue(sync.contains("current.copy(manga=current.sourceManga.copy(chapters=chapters))"))
		assertTrue(sync.contains("if(mangaDataRepository.isScanlatorsMerged(mangaId))"))
	}

	private fun source(relativePath: String): String = sequenceOf(
		File("src/main/kotlin", relativePath),
		File("app/src/main/kotlin", relativePath),
	).first(File::isFile).readText()
		.replace(Regex("""//[^\r\n]*"""), "")
		.replace(Regex("""\s+"""), "")
}
