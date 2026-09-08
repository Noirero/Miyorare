package org.koitharu.kotatsu.reader.ui.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsPrivacyPolicyTest {
	@Test fun `private only always hides title`() =
		assertTrue(shouldHideTtsTitle(isPrivateOnly = true, rememberedSensitive = false, currentSensitive = false))

	@Test fun `normal unlocked reader may show title`() =
		assertFalse(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = false, currentSensitive = false))

	@Test fun `remembered secure state hides title in background`() =
		assertTrue(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = true, currentSensitive = false))

	@Test fun `current secure state hides title`() =
		assertTrue(shouldHideTtsTitle(isPrivateOnly = false, rememberedSensitive = false, currentSensitive = true))
}
