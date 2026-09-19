package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalStackSafetyRegressionTest {

	@Test
	fun `Local index rebuild does not nest MangaDataRepository transactions inside index swap`() {
		val source = source("org/koitharu/kotatsu/local/data/index/LocalMangaIndex.kt")
		val rebuild = source
			.substringAfter("privatesuspendfunrebuildIndexLocked()=withContext(Dispatchers.IO){")
			.substringBefore("currentVersion=VERSION")
		val swap = rebuild.substringAfter("db.withTransaction{").substringBefore("currentVersion=VERSION")

		assertTrue(
			"Scanned manga metadata must be persisted before the atomic local-index swap",
			rebuild.indexOf("mangaDataRepository.storeManga(manga.manga,replaceExisting=true)") <
				rebuild.indexOf("db.withTransaction{"),
		)
		assertFalse(
			"Index swap must not invoke MangaDataRepository.storeManga(), which opens a nested Room transaction",
			swap.contains("mangaDataRepository.storeManga("),
		)
		assertTrue(swap.contains("dao.upsert(manga.toEntity())"))
	}

	@Test
	fun `Local cover discovery uses an explicit directory stack instead of recursive folder calls`() {
		val source = source("org/koitharu/kotatsu/local/data/input/LocalMangaParser.kt")
		val discovery = source
			.substringAfter("privatefunFileSystem.findFirstImageUri(")
			.substringBefore("privatefunFile.findFirstPdf(")

		assertTrue(discovery.contains("valpending=ArrayDeque<Path>()"))
		assertTrue(discovery.contains("while(pending.isNotEmpty())"))
		assertFalse(
			"Directory traversal must not recurse through findFirstImageUri(file, ...)",
			discovery.contains("findFirstImageUri(file,"),
		)
		assertTrue(source.contains("MAX_NESTED_COVER_ARCHIVE_DEPTH=8"))
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
