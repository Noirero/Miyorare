package org.koitharu.kotatsu.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonExtensionLoaderTest {

	@Test
	fun `normalizeSourceClassNames resolves relative class names`() {
		val result = MihonExtensionLoader.normalizeSourceClassNames(
			pkgName = "eu.kanade.tachiyomi.extension.zh.demo",
			sourceClassNames = ".DemoSource; foo.bar.AbsoluteSource, .Factory",
		)

		assertEquals(
			listOf(
				"eu.kanade.tachiyomi.extension.zh.demo.DemoSource",
				"foo.bar.AbsoluteSource",
				"eu.kanade.tachiyomi.extension.zh.demo.Factory",
			),
			result,
		)
	}

	@Test
	fun `source class lookup ignores non-string package metadata`() {
		val metadata = mapOf<String, Any?>(
			"org.chromium.content.browser.NUM_SANDBOXED_SERVICES" to 5,
			"tachiyomi.extension.nsfw" to true,
			"tachiyomi.extension.class" to 123,
			"tachiyomi.novelextension.factory" to ".NovelFactory",
		)
		assertEquals(".NovelFactory", MihonExtensionLoader.readSourceClassNames(metadata))
		assertEquals(
			".AnimeFactory",
			MihonExtensionLoader.readSourceClassNames(
				mapOf("com.example.animeextension.factory" to ".AnimeFactory"),
			),
		)
		assertEquals(
			null,
			MihonExtensionLoader.readSourceClassNames(
				mapOf<String, Any?>(
					"org.chromium.content.browser.NUM_SANDBOXED_SERVICES" to 5,
					"tachiyomi.extension.class" to false,
				),
			),
		)
	}

	@Test
	fun `parseNsfwFlag supports integer metadata`() {
		assertTrue(MihonExtensionLoader.parseNsfwFlag(1))
	}

	@Test
	fun `parseNsfwFlag returns false when metadata is absent`() {
		assertFalse(MihonExtensionLoader.parseNsfwFlag(null))
	}

	@Test
	fun `isSupportedLibVersion matches Mihon ABI range`() {
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(1.2))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.4))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.5))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.6))
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(1.9))
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(2.0))
	}
}
