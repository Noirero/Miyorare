package org.koitharu.kotatsu.tsuki

import eu.kanade.tachiyomi.source.model.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class ExHentaiDynamicFiltersTest {

	private val source = MangaSource(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)

	@Test
	fun `filter hierarchy mirrors Mihon E-Hentai controls`() {
		val filters = ExHentaiDynamicFilters.create()
		assertEquals(
			listOf(
				"Enforce language",
				"Favorites",
				"Watched List",
				"Genres",
				"Separate tags with commas (,)",
				"Prepend with dash (-) to exclude",
				"Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories.",
				"Tags",
				"Female Tags",
				"Male Tags",
				"Advanced Options",
			),
			filters.map { it.name },
		)
		val genres = (filters[3] as Filter.Group<*>).state.filterIsInstance<Filter.CheckBox>()
		assertEquals(
			listOf("Dōjinshi", "Manga", "Artist CG", "Game CG", "Western", "Non-H", "Image Set", "Cosplay", "Asian Porn", "Misc"),
			genres.map { it.name },
		)
		val advanced = (filters[10] as Filter.Group<*>).state.filterIsInstance<Filter<*>>()
		assertEquals(11, advanced.size)
		assertTrue((advanced[0] as Filter.CheckBox).state)
		assertTrue((advanced[1] as Filter.CheckBox).state)
		assertFalse((advanced[2] as Filter.CheckBox).state)
	}

	@Test
	fun `default search stays a plain query with no synthetic filter controls`() {
		val parserFilter = ExHentaiDynamicFilters.toParserFilter(
			encoded = MangaListFilter(query = "gragas743"),
			source = source,
			preferredLocale = Locale.ENGLISH,
		)
		assertEquals("gragas743", parserFilter.query)
		assertTrue(controls(parserFilter).isEmpty())
		assertFalse(parserFilter.tags.any { it.key.contains("ai generated", ignoreCase = true) })
		assertFalse(parserFilter.tags.any { it.key == "misc" })
	}

	@Test
	fun `selected genre tags and advanced values round trip into semantic controls`() {
		val defaults = ExHentaiDynamicFilters.create()
		val working = ExHentaiDynamicFilters.create()
		val genres = (working[3] as Filter.Group<*>).state.filterIsInstance<Filter.CheckBox>()
		genres.last().state = true
		(working[7] as Filter.Text).state = "foo, -bar"
		val advanced = (working[10] as Filter.Group<*>).state.filterIsInstance<Filter<*>>()
		(advanced[7] as Filter.CheckBox).state = true
		(advanced[8] as Filter.Select<*>).state = 3
		(advanced[9] as Filter.Text).state = "10"
		(advanced[10] as Filter.Text).state = "100"

		val encodedTags = MihonFilterMapper.encode(working, defaults, source)
		val parserFilter = ExHentaiDynamicFilters.toParserFilter(
			encoded = MangaListFilter(query = "gragas", tags = encodedTags),
			source = source,
			preferredLocale = Locale.ENGLISH,
		)
		val controls = controls(parserFilter)
		assertEquals("1022", controls["f_cats"])
		assertEquals("foo, -bar", controls["q_tag"])
		assertEquals("on", controls["f_sh"])
		assertEquals("4", controls["f_srdd"])
		assertEquals("10", controls["f_spf"])
		assertEquals("100", controls["f_spt"])
		assertFalse(controls.containsKey("f_sr"))
		assertFalse(controls.containsKey("f_sp"))
	}

	private fun controls(filter: MangaListFilter): Map<String, String> = filter.tags.associate { tag ->
		val raw = tag.key.removePrefix(ExHentaiDynamicFilters.CONTROL_PREFIX)
		val separator = raw.indexOf('=')
		val name = raw.substring(0, separator)
		val value = URLDecoder.decode(raw.substring(separator + 1), StandardCharsets.UTF_8.name())
		name to value
	}
}
