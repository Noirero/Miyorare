package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry

class ReaderJourneyCosmeticSnapshotCodecTest {

	@Test
	fun `snapshot round trip preserves one coherent loadout`() {
		val loadout = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.NEON_ARCHIVE.stableId,
			navigationThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			accentThemeId = RankThemeId.IMPERIAL_AURORA.stableId,
			glowThemeId = RankThemeId.GOLDEN_MANUSCRIPT.stableId,
			selectedBadgeId = "ARCHIVIST_BADGE",
			selectedWallpaperId = "ARCHIVIST_WALLPAPER",
			selectedFrameId = "ARCHIVIST_FRAME",
			selectedNameplateId = "ARCHIVIST_NAMEPLATE",
			selectedReaderCardId = "ARCHIVIST_CARD",
			selectedProgressStyleId = "ARCHIVIST_PROGRESS",
			frame = ReaderRank.READER,
			glow = ReaderRank.BOOKWORM,
			background = ReaderRank.EXPLORER,
			progressBar = ReaderRank.COLLECTOR,
			favoriteThemeIds = setOf(
				RankThemeId.NEON_ARCHIVE.stableId,
				RankThemeId.GOLDEN_MANUSCRIPT.stableId,
			),
			autoEquipNewRankTheme = true,
		)

		val decoded = ReaderJourneyCosmeticSnapshotCodec.decode(
			ReaderJourneyCosmeticSnapshotCodec.encode(loadout),
		)

		assertEquals(loadout, decoded)
	}

	@Test
	fun `unknown persisted theme ids sanitize safely`() {
		val decoded = ReaderJourneyCosmeticSnapshotCodec.decode(
			"v=2|mode=FULL_SET|theme=DOES_NOT_EXIST|badge=ok_badge|wallpaper=|card=|progressStyle=|frame=LEGEND|glow=|background=|progress=|favorites=DOES_NOT_EXIST|autoEquip=1",
		)

		requireNotNull(decoded)
		assertEquals(ReaderJourneyCosmeticLoadout.SCHEMA_VERSION, decoded.schemaVersion)
		assertNull(decoded.selectedThemeId)
		assertNull(decoded.navigationThemeId)
		assertNull(decoded.accentThemeId)
		assertNull(decoded.glowThemeId)
		assertEquals(
			RankThemeVisualRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)?.frameId,
			decoded.selectedFrameId,
		)
		assertNull(decoded.selectedNameplateId)
		assertTrue(decoded.favoriteThemeIds.isEmpty())
		assertEquals(ReaderRank.LEGEND, decoded.frame)
		assertTrue(decoded.autoEquipNewRankTheme)
	}

	@Test
	fun `invalid schema fails closed instead of partially applying`() {
		assertNull(
			ReaderJourneyCosmeticSnapshotCodec.decode(
				"v=1|mode=CUSTOM|theme=ARCHIVIST_NEON_ARCHIVE",
			),
		)
		assertNull(ReaderJourneyCosmeticSnapshotCodec.decode("corrupt"))
	}

	@Test
	fun `snapshot contains no rank ordinal or display name identity`() {
		val encoded = ReaderJourneyCosmeticSnapshotCodec.encode(
			ReaderJourneyCosmeticLoadout(
				selectedThemeId = RankThemeId.NEON_ARCHIVE.stableId,
			),
		)
		assertTrue(encoded.contains(RankThemeId.NEON_ARCHIVE.stableId))
		assertFalse(encoded.contains(RankThemeId.NEON_ARCHIVE.displayName))
		assertFalse(encoded.contains("ordinal"))
	}
}
