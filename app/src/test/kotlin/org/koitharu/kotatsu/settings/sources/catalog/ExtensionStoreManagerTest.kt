package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStoreManagerTest {

	@Test
	fun `failed refresh keeps the previous catalog and marks store unavailable`() {
		val store = record("one", "Store")
		val cached = listOf(entry("cached.extension"))
		val previous = ExtensionStoreState(store, StoreHealth.AVAILABLE, cached)

		val next = storeStateAfterRefresh(store, previous, Result.failure(IllegalStateException("offline")))

		assertEquals(StoreHealth.UNAVAILABLE, next.health)
		assertEquals(cached, next.catalog)
		assertTrue(next.error is IllegalStateException)
	}

	@Test
	fun `first load after legacy migration bypasses the stale http cache`() {
		assertTrue(shouldForceStoreRefresh(forceRefresh = false, migrationPerformed = true))
	}

	@Test
	fun `colliding original names are disambiguated only in labels`() {
		val stores = listOf(
			record("one", "Community", "https://one.example/repo"),
			record("two", "Community", "https://two.example/repo"),
			record("three", "Unique", "https://three.example/repo"),
		)

		val labels = extensionStoreDisplayLabels(stores)

		assertEquals("Community · one.example", labels.getValue("one"))
		assertEquals("Community · two.example", labels.getValue("two"))
		assertEquals("Unique", labels.getValue("three"))
		assertEquals("Community", stores.first().name)
	}

	@Test
	fun `anime repository is rejected from manga and novel categories`() {
		val catalog = listOf(entry("eu.kanade.tachiyomi.animeextension.all.example"))

		for (selected in listOf(ExtensionStoreContentType.MANGA, ExtensionStoreContentType.NOVEL)) {
			val error = runCatching {
				validateExtensionStoreContentType(catalog, selected)
			}.exceptionOrNull()
			assertTrue(error is ExtensionStoreContentTypeMismatchException)
			assertEquals(
				setOf(ExtensionStoreContentType.ANIME),
				(error as ExtensionStoreContentTypeMismatchException).detectedTypes,
			)
		}

		validateExtensionStoreContentType(catalog, ExtensionStoreContentType.ANIME)
	}

	@Test
	fun `novel repository is rejected from manga category`() {
		val catalog = listOf(entry("eu.kanade.tachiyomi.novelextension.en.example"))
		val error = runCatching {
			validateExtensionStoreContentType(catalog, ExtensionStoreContentType.MANGA)
		}.exceptionOrNull()

		assertTrue(error is ExtensionStoreContentTypeMismatchException)
		assertEquals(
			setOf(ExtensionStoreContentType.NOVEL),
			(error as ExtensionStoreContentTypeMismatchException).detectedTypes,
		)
	}

	@Test
	fun `mixed manga and novel repository permits either published category`() {
		val catalog = listOf(
			entry("eu.kanade.tachiyomi.extension.en.example"),
			entry("eu.kanade.tachiyomi.novelextension.en.example"),
		)

		validateExtensionStoreContentType(catalog, ExtensionStoreContentType.MANGA)
		validateExtensionStoreContentType(catalog, ExtensionStoreContentType.NOVEL)
	}

	private fun record(id: String, name: String, url: String = "https://$id.example/repo") =
		ExtensionStoreRecord(id, normalizeExtensionStoreUrl(url), name)

	private fun entry(pkg: String) = ExternalExtensionRepoEntry(
		name = pkg,
		packageName = pkg,
		apkName = "$pkg.apk",
		versionCode = 1,
		versionName = "1.4.1",
	)
}
