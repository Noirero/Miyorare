package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetailsDownloadResolutionBoundaryTest {

	@Test
	fun `Details delegates downloaded artifact resolution to dedicated resolver`() {
		val details = source("org/koitharu/kotatsu/details/domain/DetailsLoadUseCase.kt")

		assertTrue(details.contains("privatevaldownloadedMangaResolver:DownloadedMangaResolver"))
		assertTrue(details.contains("downloadedMangaResolver.resolveCanonicalManga(resolvedIntentManga)"))
		assertTrue(details.countOccurrences("downloadedMangaResolver.findSavedManga(") >= 2)

		for (forbidden in listOf(
			"FavouriteDownloadIndexEntity",
			"DownloadDestinationStore",
			"LocalMangaIndex",
			"getFavouriteDownloadIndexDao()",
			"findSavedMangaInRoot(",
			"privatefunFile.isInside(",
		)) {
			assertFalse("Details must not own download-resolution primitive: $forbidden", details.contains(forbidden))
		}
	}

	@Test
	fun `indexed resolver keeps ownership first and never broad-scans roots`() {
		val resolver = source("org/koitharu/kotatsu/local/domain/DownloadedMangaResolver.kt")
		val indexedBlock = resolver
			.substringAfter("if(preferIndexed){")
			.substringBefore("if(favouriteSpace!=null){for(rootin")

		val ownership = indexedBlock.indexOf("findEntry(favouriteSpace.dbValue,manga.id)")
		val indexed = indexedBlock.indexOf("findSavedMangaIndexed(manga)")
		assertTrue("FavouriteSpace ownership must be checked before global index fallback", ownership >= 0 && ownership < indexed)
		assertFalse(
			"Indexed Details/Reader hot path must never invoke the broad reconnect root scan",
			indexedBlock.contains("findSavedMangaInRoot("),
		)
		assertTrue(indexedBlock.contains("favouriteSpace==FavouriteSpace.PRIVATE"))
		assertTrue(indexedBlock.contains("favouriteSpace==FavouriteSpace.NORMAL&&downloadDestinationStore.privateUsesOwnRoot()"))
		assertTrue(indexedBlock.contains("rememberFavouriteDownloadOwnership(favouriteSpace,manga.id,indexed.file)"))
	}

	@Test
	fun `canonical Local identity keeps offline fallback contract`() {
		val resolver = source("org/koitharu/kotatsu/local/domain/DownloadedMangaResolver.kt")
		val canonical = resolver
			.substringAfter("suspendfunresolveCanonicalManga(manga:Manga):Manga{")
			.substringBefore("suspendfunfindSavedManga(")

		assertTrue(canonical.contains("getCanonicalRemoteIds(listOf(manga.id))"))
		assertTrue(canonical.contains("mangaDataRepository.findMangaById(remoteId,withChapters=true)"))
		assertTrue(canonical.contains("mihonExtensionManager.ensureReady(forceRefresh=false)"))
		assertTrue(canonical.contains("returnif(remote.source.isBroken)mangaelseremote"))
	}

	private fun String.countOccurrences(value: String): Int {
		var count = 0
		var offset = 0
		while (true) {
			val index = indexOf(value, offset)
			if (index < 0) return count
			count++
			offset = index + value.length
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
