package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcePackDetailsFirstRenderRegressionTest {

	@Test
	fun `Source Pack complete chapters publish before Details persistence`() {
		val sourcePack = source("org/koitharu/kotatsu/lnreader/LnMangaRepository.kt")
		val details = source("org/koitharu/kotatsu/details/domain/DetailsLoadUseCase.kt")

		val complete = sourcePack.indexOf("valcomplete=toDetailsManga(manga,novel,chapters)")
		val publish = sourcePack.indexOf("onIntermediate(complete)", startIndex = complete)
		val returned = sourcePack.indexOf("returncomplete", startIndex = publish)
		assertTrue(complete >= 0)
		assertTrue(publish > complete)
		assertTrue(returned > publish)
		assertTrue(sourcePack.contains("if(!complete.chapters.isNullOrEmpty()){onIntermediate(complete)}"))

		val progressiveCall = details.indexOf("progressiveRepository.getDetailsProgressively(cachedBeforeFetch){partial->")
		val provisional = details.indexOf("isLoaded=false", startIndex = progressiveCall)
		val store = details.indexOf("mangaDataRepository.storeManga(", startIndex = provisional)
		assertTrue(progressiveCall >= 0)
		assertTrue(provisional > progressiveCall)
		assertTrue(store > provisional)
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
