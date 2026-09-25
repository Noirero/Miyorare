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
	@Test
	fun `rank 90 and 100 use distinct approved prism identities`() {
		assertEquals("Imperial Aurora", RankThemeId.IMPERIAL_AURORA.displayName)
		assertEquals("Eternal Library", RankThemeId.ETERNAL_LIBRARY.displayName)

		val rank90 = RankThemeRegistry.resolveOrDefault(RankThemeId.IMPERIAL_AURORA.stableId)
			.tokens(RankThemeVariant.DARK)
		val rank100 = RankThemeRegistry.resolveOrDefault(RankThemeId.ETERNAL_LIBRARY.stableId)
			.tokens(RankThemeVariant.DARK)

		assertNotEquals(rank90.background, rank100.background)
		assertNotEquals(rank90.primaryAccent, rank100.primaryAccent)
		assertNotEquals(rank90.secondaryAccent, rank100.secondaryAccent)
		assertEquals(0xFF8B5CF6L, rank90.primaryAccent)
		assertEquals(0xFF42E5F2L, rank90.secondaryAccent)
		assertEquals(0xFFF7FAFFL, rank100.primaryAccent)
		assertEquals(0xFFFFD996L, rank100.secondaryAccent)
	}

	@Test
	fun `rank 90 and 100 signature profiles keep motion slow and hierarchy restrained`() {
		val rank90 = checkNotNull(RankThemeSignatureRegistry.resolve(RankThemeId.IMPERIAL_AURORA))
		val rank100 = checkNotNull(RankThemeSignatureRegistry.resolve(RankThemeId.ETERNAL_LIBRARY))

		assertTrue(rank90.borderShiftMs in 8_000..16_000)
		assertTrue(rank90.badgeShimmerMs in 4_000..8_000)
		assertTrue(rank90.auroraDriftMs in 20_000..40_000)

		assertTrue(rank100.borderShiftMs in 10_000..20_000)
		assertTrue(rank100.badgeShimmerMs in 6_000..12_000)
		assertTrue(rank100.auroraDriftMs in 20_000..40_000)
		assertTrue(rank100.staticStarCount <= 20)
		assertTrue(rank100.signatureSparkleCount <= 5)
		assertTrue(rank100.selectedSheenOnce)
		assertNotEquals(rank90.borderStops, rank100.borderStops)
	}

}
