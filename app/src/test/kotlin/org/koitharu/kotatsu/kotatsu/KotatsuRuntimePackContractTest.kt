package org.koitharu.kotatsu.kotatsu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimePackContract
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimePackManifest

class KotatsuRuntimePackContractTest {

	@Test
	fun `source identity is stable and revision independent`() {
		val first = KotatsuMangaSource("MANGADEX", "MangaDex", "en", "MANGA")
		val afterUpdate = KotatsuMangaSource("MANGADEX", "MangaDex", "en", "MANGA")
		assertEquals("KOTATSU:MANGADEX", first.name)
		assertEquals(first.name, afterUpdate.name)
		assertEquals("MANGADEX", KotatsuMangaSource.sourceKeyOf(first.name))
		assertNull(KotatsuMangaSource.sourceKeyOf("TSUKI:KOTATSU:MANGADEX"))
	}

	@Test
	fun `manifest accepts only current host protocol`() {
		val manifest = KotatsuRuntimePackManifest(
			protocolVersion = KotatsuRuntimePackContract.PROTOCOL_VERSION,
			packVersion = "1",
			upstreamRevision = "dc223c3d8098415b29cf1acfedc401a88d01c9fe",
			sourceCount = 1,
			sha256 = "0".repeat(64),
			downloadUrl = "https://github.com/Noirero/Miyorare/releases/download/kotatsu-runtime-latest/runtime.apk",
		)
		assertTrue(manifest.isHostCompatible)
	}

	@Test
	fun `manifest rejects abbreviated upstream revision`() {
		val error = runCatching {
			KotatsuRuntimePackManifest(
				protocolVersion = KotatsuRuntimePackContract.PROTOCOL_VERSION,
				packVersion = "1",
				upstreamRevision = "dc223c3",
				sourceCount = 1,
				sha256 = "0".repeat(64),
				downloadUrl = "https://github.com/Noirero/Miyorare/releases/download/kotatsu-runtime-latest/runtime.apk",
			)
		}.exceptionOrNull()
		assertNotNull(error)
	}
}
