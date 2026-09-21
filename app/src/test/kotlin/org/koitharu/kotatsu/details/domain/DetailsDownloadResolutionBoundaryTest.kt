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
	fun `download resolver has one indexed primary path and no broad reconnect branch`() {
		val resolver = source("org/koitharu/kotatsu/local/domain/DownloadedMangaResolver.kt")
		val local = source("org/koitharu/kotatsu/local/data/LocalMangaRepository.kt")
		val resolverBlock = resolver
			.substringAfter("suspendfunfindSavedManga(")
			.substringBefore("privatesuspendfunrememberFavouriteDownloadOwnership")

		val ownership = resolverBlock.indexOf("findEntry(favouriteSpace.dbValue,manga.id)")
		val indexed = resolverBlock.indexOf("findSavedMangaIndexed(manga)")
		assertTrue("FavouriteSpace ownership must be checked before global index fallback", ownership >= 0 && ownership < indexed)
		assertFalse("Resolver must not expose an old/new execution switch", resolverBlock.contains("preferIndexed"))
		assertFalse("Resolver hot path must never invoke root-by-root reconnect scanning", resolverBlock.contains("findSavedMangaInRoot("))
		assertTrue(resolverBlock.contains("findSavedMangaIndexedByTitle("))

		val localBlock = local
			.substringAfter("suspendfunfindSavedManga(remoteManga:Manga,withDetails:Boolean=true)")
			.substringBefore("override suspend fun getPageUrl".replace(" ", ""))
		assertFalse(
			"Normal saved-manga lookup must not restore the obsolete broad LocalMangaParser.find scan",
			localBlock.contains("LocalMangaParser.find("),
		)
		assertTrue(localBlock.contains("findSavedMangaIndexedByTitle("))
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
