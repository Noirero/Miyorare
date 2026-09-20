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

	@Test
	fun `Local list waits for current scanner version before first render`() {
		val repository = source("org/koitharu/kotatsu/local/data/LocalMangaRepository.kt")
		val viewModel = source("org/koitharu/kotatsu/local/ui/LocalListViewModel.kt")
		val getList = repository
			.substringAfter("overridesuspendfungetList(")
			.substringBefore("privatefunbuildFilteredList(")

		val rebuild = getList.indexOf("localMangaIndex.rebuildIfRequired()")
		val snapshot = getList.indexOf("localMangaIndex.getAll()")
		assertTrue(
			"Local inventory must finish a version-stale rebuild before exposing its first snapshot",
			rebuild >= 0 && rebuild < snapshot,
		)
		assertFalse(
			"LocalListViewModel must not render stale state and patch it from a second rebuild coroutine",
			viewModel.contains("localMangaIndex.rebuildIfRequired()"),
		)
	}


	@Test
	fun \`cold app start stays off full Local filesystem rebuild\`() {
		val mainActivity = source("org/koitharu/kotatsu/main/ui/MainActivity.kt")
		val localViewModel = source("org/koitharu/kotatsu/local/ui/LocalListViewModel.kt")

		assertFalse(
			"Fresh MainActivity must not start the retired full Local index scan service",
			mainActivity.contains("LocalIndexUpdateService"),
		)
		assertFalse(
			"Fresh MainActivity must not call LocalMangaIndex.update() directly",
			mainActivity.contains("localMangaIndex.update()"),
		)
		assertTrue(
			"Explicit Local refresh must retain the full discovery path",
			localViewModel.contains("localMangaIndex.update()"),
		)
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
