package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersistentCoverStorageRegressionTest {

	@Test
	fun `collection covers use a compact bounded Data representation`() {
		val store = source("core/image/StoredMangaCoverStore.kt").compact()
		val legacy = source("core/image/MiyorareImageDiskCache.kt").compact()

		assertTrue(store.contains("File(context.filesDir,DIRECTORY_NAME)"))
		assertTrue(store.contains("constvalMAX_EDGE=512"))
		assertTrue(store.contains("constvalJPEG_QUALITY=82"))
		assertTrue(store.contains("constvalMAX_BYTES=96L*1024*1024"))
		assertTrue(store.contains("constvalMAX_ENTRY_BYTES=2*1024*1024"))
		assertTrue(store.contains("Bitmap.CompressFormat.JPEG"))
		assertTrue(store.contains("Bitmap.CompressFormat.PNG"))
		assertTrue(store.contains("imageDiskCache.remove(spec.rawDiskCacheKey)"))
		assertTrue(store.contains("suspendfunremoveManga(mangaId:Long)"))
		assertTrue(store.contains("storageOrNull"))

		assertTrue(legacy.contains(".maxSizePercent(0.01)"))
		assertTrue(legacy.contains(".minimumMaxSizeBytes(8L*1024L*1024L)"))
		assertTrue(legacy.contains(".maximumMaxSizeBytes(64L*1024L*1024L)"))
	}

	@Test
	fun `only collection surfaces opt into durable remote covers`() {
		val coil = source("core/util/ext/Coil.kt").compact()
		val coverView = source("image/ui/CoverImageView.kt").compact()
		val adapter = source("list/ui/adapter/MangaListAdapter.kt").compact()
		val list = source("list/ui/adapter/MangaListItemAD.kt").compact()
		val detailed = source("list/ui/adapter/MangaListDetailedItemAD.kt").compact()
		val grid = source("list/ui/adapter/MangaGridItemAD.kt").compact()
		val favourites = source("favourites/ui/list/FavouritesListFragment.kt").compact()

		assertTrue(coil.contains("persist:Boolean?=null,forceRefresh:Boolean=false"))
		assertTrue(coil.contains("valshouldPersist=persist?:(!forceRefresh&&build().extras[mangaKey]!=null)"))
		assertTrue(coil.contains("isRemoteCoverUrl(coverUrl)"))
		assertTrue(coil.contains("mangaCoverOverrideDiskCacheKey(manga.id,coverUrl)"))
		assertTrue(coil.contains("rawDiskCacheKey=rawDiskCacheKey"))
		assertFalse(
			"Smart Local owns its own persistent derived thumbnails",
			coil.substringAfter("funImageRequest.Builder.stableMangaCoverKey(")
				.substringBefore("funmangaCoverDiskCacheKey")
				.contains("isSmartLocalCoverUrl"),
		)
		assertTrue(coverView.contains("persistCover:Boolean=false"))
		assertTrue(adapter.contains("mangaListItemAD(listener,titleClickListener,coverContext)"))
		assertTrue(adapter.contains("mangaListDetailedItemAD(listener,titleClickListener,coverContext)"))
		for (delegate in listOf(list, detailed, grid)) {
			assertTrue(delegate.contains("persistCover=coverContext==ModernCoverContext.COLLECTION"))
		}
		assertTrue(
			favourites.contains(
				".mangaExtra(candidate.item.manga).stableMangaCoverKey(candidate.item.manga,candidate.coverUrl)",
			),
		)
	}

	@Test
	fun `details refresh updates stored covers without making every viewed manga durable`() {
		val details = source("details/ui/HeroSectionComponents.kt").compact()
		val pipeline = source("core/image/StoredMangaCoverStore.kt").compact()

		assertTrue(details.contains("stableMangaCoverKey(manga,coverUrl,forceRefresh=true)"))
		assertFalse(details.contains("diskCacheKey(mangaCoverDiskCacheKey(manga.id))"))
		assertTrue(pipeline.contains("storageOrNull{store.containsManga(spec.mangaId)}==true"))
		assertTrue(pipeline.contains("valshouldPersist=spec.persist||(spec.forceRefresh&&hadStoredCover)"))
		assertTrue(pipeline.contains("store.removeManga(spec.mangaId)"))
		assertTrue(pipeline.contains("Detailsrefreshesmustnotturnamerelyviewedmangaintodurableappdata"))
	}

	@Test
	fun `stored cover cleanup reports and clears every durable cover owner`() {
		val cleanup = source("settings/userdata/storage/DataCleanupSettingsViewModel.kt").compact()
		val label = resource("values/strings_persistent_cover_cache.xml")

		assertTrue(cleanup.contains("imageDiskCache.coverSize+storedMangaCoverStore.size()+smartLocalCoverCache.size()"))
		assertTrue(cleanup.contains("imageDiskCache.clearCovers()"))
		assertTrue(cleanup.contains("storedMangaCoverStore.clear()"))
		assertTrue(cleanup.contains("smartLocalCoverCache.clear()"))
		assertTrue(label.contains(">Clear stored covers<"))
	}

	private fun String.compact() = replace(Regex("\\s+"), "")

	private fun source(path: String): String = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()

	private fun resource(path: String): String = sequenceOf(
		File("src/main/res", path),
		File("app/src/main/res", path),
	).first(File::isFile).readText()
}
