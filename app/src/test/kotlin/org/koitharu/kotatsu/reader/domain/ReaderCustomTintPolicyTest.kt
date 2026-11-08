package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs

class ReaderCustomTintPolicyTest {
    @Test fun disabledDefaultsPreserveUnfilteredReaderAndOldData() {
        assertTrue(ReaderColorFilter.EMPTY.isEmpty)
        assertFalse(ReaderColorFilter.EMPTY.copy(custom = ReaderCustomTint(enabled = true)).isEmpty)
        assertFalse(ReaderColorFilter.EMPTY.copy(custom = ReaderCustomTint(red = 180)).isEmpty)
        assertEquals(ReaderTintBlendMode.OVERLAY, ReaderTintBlendMode.fromStored(null))
        assertEquals(ReaderTintBlendMode.OVERLAY, ReaderTintBlendMode.fromStored("UNKNOWN"))
        ReaderTintBlendMode.entries.forEach { assertEquals(it, ReaderTintBlendMode.fromStored(it.name)) }
    }

    @Test fun syncRoundTripIncludesOptionalTintWithoutBreakingLegacyPayload() {
        val base = MangaPrefsEntity(
            mangaId = 2L, mode = 1, cfBrightness = 0f, cfContrast = 0f,
            cfInvert = false, cfGrayscale = false, cfBookEffect = false,
            titleOverride = null, coverUrlOverride = null, contentRatingOverride = null,
        )
        val legacy = SyncMangaPrefs(base)
        assertFalse(legacy.cfCustomEnabled)
        assertEquals("OVERLAY", legacy.cfCustomMode)
        val custom = SyncMangaPrefs(
            base.copy(cfCustomEnabled = true, cfCustomRed = 210,
                cfCustomGreen = 23, cfCustomBlue = 87, cfCustomAlpha = 180,
                cfCustomMode = "MULTIPLY"),
        ).toEntity()
        assertTrue(custom.cfCustomEnabled)
        assertEquals(210, custom.cfCustomRed)
        assertEquals(23, custom.cfCustomGreen)
        assertEquals(87, custom.cfCustomBlue)
        assertEquals(180, custom.cfCustomAlpha)
        assertEquals("MULTIPLY", custom.cfCustomMode)
    }
}
