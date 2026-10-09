package org.koitharu.kotatsu.favourites.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Batch11FollowupRegressionTest {

	@Test
	fun `Details observes favourite categories in its active library space`() {
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")
		val interactor = source("org/koitharu/kotatsu/details/domain/DetailsInteractor.kt")

		assertTrue(details.contains("interactor.observeFavourite(mangaId,favouriteSpace)"))
		assertTrue(interactor.contains("favouritesRepository.observeCategories(mangaId,space)"))
		assertFalse(details.contains("interactor.observeFavourite(mangaId)"))
	}

	@Test
	fun `legacy reconciliation repairs a stale empty local index before giving up`() {
		val reconciler = source("org/koitharu/kotatsu/favourites/domain/LegacyFavouriteDownloadReconciler.kt")

		val emptyCheck = reconciler.indexOf("if(localSnapshot.isEmpty()){")
		val rebuild = reconciler.indexOf("localMangaIndex.rebuildIfRequired()", emptyCheck)
		val reload = reconciler.indexOf("localSnapshot=localMangaIndex.getPersistedSnapshot()", rebuild)
		val finalEmptyCheck = reconciler.indexOf("if(localSnapshot.isEmpty())return", reload)

		assertTrue(emptyCheck >= 0)
		assertTrue(rebuild > emptyCheck)
		assertTrue(reload > rebuild)
		assertTrue(finalEmptyCheck > reload)
	}

	@Test
	fun `shared roots never infer Normal or Private ownership from path alone`() {
		val classifier = source("org/koitharu/kotatsu/favourites/domain/DownloadedContentClassifier.kt")
		val ownership = source("org/koitharu/kotatsu/favourites/domain/FavouriteDownloadOwnershipIndex.kt")
		val resolver = source("org/koitharu/kotatsu/local/domain/DownloadedMangaResolver.kt")

		assertTrue(classifier.contains("privateUsesOwnRoot()&&!downloadDestinationStore.rootsOverlap()"))
		assertTrue(ownership.contains("!downloadDestinationStore.privateUsesOwnRoot()||downloadDestinationStore.rootsOverlap()"))
		assertTrue(resolver.contains("if(!canInferSpaceFromPath())returnnull"))
	}

	private fun source(relativePath: String): String {
		var dir = File(System.getProperty("user.dir"))
		repeat(8) {
			val candidate = File(dir, "src/main/kotlin/$relativePath")
			if (candidate.isFile) return candidate.readText().filterNot(Char::isWhitespace)
			dir = dir.parentFile ?: return@repeat
		}
		error("Cannot locate source file: $relativePath")
	}
}
