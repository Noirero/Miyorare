package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceFamily
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.ExploreSourceOriginFilter
import org.koitharu.kotatsu.explore.ui.adapter.family
import org.koitharu.kotatsu.explore.ui.adapter.formatExploreSourceListSummary
import org.koitharu.kotatsu.explore.ui.adapter.matches
import org.koitharu.kotatsu.explore.ui.adapter.toExploreSourceOrigin
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

class ExploreSourceProvenanceTest {

	@Test
	fun `tsuki providers keep their real provenance`() {
		assertEquals(ExploreSourceOrigin.MIYORARE_PACK, TsukiPluginProvider.MIYORARE.toExploreSourceOrigin())
		assertEquals(ExploreSourceOrigin.UMA, TsukiPluginProvider.UMA.toExploreSourceOrigin())
		assertEquals(ExploreSourceOrigin.GEKKOUSHI, TsukiPluginProvider.GEKKOUSHI.toExploreSourceOrigin())
		assertEquals(ExploreSourceOrigin.CUSTOM_PLUGIN, TsukiPluginProvider.CUSTOM.toExploreSourceOrigin())
	}

	@Test
	fun `plugin family contains optional plugin ecosystems only`() {
		assertEquals(ExploreSourceFamily.MIYORARE_PACKS, ExploreSourceOrigin.MIYORARE_PACK.family)
		assertEquals(ExploreSourceFamily.EXTENSION_STORE, ExploreSourceOrigin.EXTENSION_STORE.family)
		assertEquals(ExploreSourceFamily.PLUGINS, ExploreSourceOrigin.UMA.family)
		assertEquals(ExploreSourceFamily.PLUGINS, ExploreSourceOrigin.GEKKOUSHI.family)
		assertEquals(ExploreSourceFamily.PLUGINS, ExploreSourceOrigin.LNREADER.family)
		assertEquals(ExploreSourceFamily.PLUGINS, ExploreSourceOrigin.CUSTOM_PLUGIN.family)
	}

	@Test
	fun `origin filters do not collapse ecosystems back into third party`() {
		assertTrue(ExploreSourceOrigin.MIYORARE_PACK.matches(ExploreSourceOriginFilter.MIYORARE_PACKS))
		assertTrue(ExploreSourceOrigin.EXTENSION_STORE.matches(ExploreSourceOriginFilter.EXTENSION_STORE))
		assertTrue(ExploreSourceOrigin.UMA.matches(ExploreSourceOriginFilter.PLUGINS))
		assertTrue(ExploreSourceOrigin.GEKKOUSHI.matches(ExploreSourceOriginFilter.GEKKOUSHI))
		assertFalse(ExploreSourceOrigin.UMA.matches(ExploreSourceOriginFilter.GEKKOUSHI))
		assertFalse(ExploreSourceOrigin.EXTENSION_STORE.matches(ExploreSourceOriginFilter.PLUGINS))
	}

	@Test
	fun `store summary shows language repository and version`() {
		assertEquals(
			"Indonesia • Kei • v1.2.3",
			formatExploreSourceListSummary(
				ExploreSourceOrigin.EXTENSION_STORE,
				"Indonesia • 1.2.3 • Kei",
				"Indonesia",
			),
		)
	}

	@Test
	fun `store summary normalizes an existing version prefix`() {
		assertEquals(
			"Indonesia • Yuzuno • v2.4.0",
			formatExploreSourceListSummary(
				ExploreSourceOrigin.EXTENSION_STORE,
				"Indonesia • v2.4.0 • Yuzuno",
				"Indonesia",
			),
		)
	}

	@Test
	fun `malformed store summary falls back safely`() {
		assertEquals(
			"Indonesia",
			formatExploreSourceListSummary(
				ExploreSourceOrigin.EXTENSION_STORE,
				"Indonesia",
				"Indonesia",
			),
		)
	}

	@Test
	fun `non store sources keep the compact summary`() {
		assertEquals(
			"Indonesia",
			formatExploreSourceListSummary(
				ExploreSourceOrigin.MIYORARE_PACK,
				"Indonesia • 1.2.3 • Miyorare",
				"Indonesia",
			),
		)
		assertEquals(
			"English",
			formatExploreSourceListSummary(
				ExploreSourceOrigin.UMA,
				"English • UMA",
				"English",
			),
		)
	}
}
