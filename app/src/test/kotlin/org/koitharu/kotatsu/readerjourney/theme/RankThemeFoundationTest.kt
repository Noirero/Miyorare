package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank

class RankThemeFoundationTest {

	@Test
	fun `registry contains exactly one stable theme per rank`() {
		assertTrue(RankThemeRegistry.validate().isEmpty())
		assertEquals(ReaderRank.entries.size, RankThemeRegistry.definitions.size)
		assertEquals(
			RankThemeId.entries.size,
			RankThemeId.entries.map { it.stableId }.distinct().size,
		)
		ReaderRank.entries.forEach { rank ->
			assertEquals(1, RankThemeRegistry.definitions.count { it.id.rank == rank })
		}
	}

	@Test
	fun `stable id is independent from display name and rank ordinal`() {
		val theme = RankThemeId.NEON_ARCHIVE
		assertEquals("ARCHIVIST_NEON_ARCHIVE", theme.stableId)
		assertEquals("Neon Archive", theme.displayName)
		assertEquals(ReaderRank.ARCHIVIST, theme.rank)
		assertEquals(theme, RankThemeId.fromStableId(theme.stableId))
		assertNull(RankThemeId.fromStableId(theme.displayName))
	}

	@Test
	fun `every theme resolves light dark and oled tokens`() {
		RankThemeRegistry.definitions.forEach { definition ->
			val light = definition.tokens(RankThemeVariant.LIGHT)
			val dark = definition.tokens(RankThemeVariant.DARK)
			val oled = definition.tokens(RankThemeVariant.OLED)
			assertNotEquals(light.background, dark.background)
			assertEquals(0xFF000000L, oled.background)
		}
	}

	@Test
	fun `reserved status colors stay independent from rank accent`() {
		val crimson = RankThemeRegistry.resolveOrDefault(RankThemeId.CRIMSON_LIBRARY.stableId)
		val tokens = crimson.tokens(RankThemeVariant.DARK)
		assertNotEquals(tokens.primaryAccent, tokens.destructiveColor)
		assertNotEquals(tokens.primaryAccent, tokens.errorColor)

		val golden = RankThemeRegistry.resolveOrDefault(RankThemeId.GOLDEN_MANUSCRIPT.stableId)
		assertNotEquals(
			golden.tokens(RankThemeVariant.LIGHT).primaryAccent,
			golden.tokens(RankThemeVariant.LIGHT).warningColor,
		)
	}

	@Test
	fun `theme source precedence is centralized and deterministic`() {
		val base = RankThemeSourceRequest(
			explicitCustomOverride = false,
			explicitRankThemeId = null,
			autoRankEnabled = false,
			currentRank = ReaderRank.ARCHIVIST,
			dynamicColorEnabled = false,
		)
		assertEquals(
			RankThemeSource.MIYORARE_DEFAULT,
			RankThemeSourceResolver.resolve(base).source,
		)
		assertEquals(
			RankThemeSource.MIYORARE_DEFAULT,
			RankThemeSourceResolver.resolve(
				base.copy(
					presentationEnabled = false,
					explicitCustomOverride = true,
					explicitRankThemeId = RankThemeId.NEON_ARCHIVE.stableId,
					autoRankEnabled = true,
					dynamicColorEnabled = true,
				),
			).source,
		)
		assertEquals(
			RankThemeSource.DYNAMIC_COLOR,
			RankThemeSourceResolver.resolve(base.copy(dynamicColorEnabled = true)).source,
		)
		assertEquals(
			RankThemeSource.AUTO_RANK,
			RankThemeSourceResolver.resolve(
				base.copy(autoRankEnabled = true, dynamicColorEnabled = true),
			).source,
		)
		assertEquals(
			RankThemeId.NEON_ARCHIVE,
			RankThemeSourceResolver.resolve(
				base.copy(autoRankEnabled = true, dynamicColorEnabled = true),
			).theme,
		)
		assertEquals(
			RankThemeSource.EXPLICIT_RANK,
			RankThemeSourceResolver.resolve(
				base.copy(
					explicitRankThemeId = RankThemeId.GOLDEN_MANUSCRIPT.stableId,
					autoRankEnabled = true,
					dynamicColorEnabled = true,
				),
			).source,
		)
		assertEquals(
			RankThemeSource.USER_CUSTOM,
			RankThemeSourceResolver.resolve(
				base.copy(
					explicitCustomOverride = true,
					explicitRankThemeId = RankThemeId.GOLDEN_MANUSCRIPT.stableId,
					autoRankEnabled = true,
					dynamicColorEnabled = true,
				),
			).source,
		)
	}
}
