package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MiyorareNeonGlassIdentityTest {

	@Test
	fun `explicit black custom accent remains black instead of luminous`() {
		val authoredBlack = 0xFF000000.toInt()
		val luminousFallback = 0xFFFFFFFF.toInt()

		assertEquals(
			authoredBlack,
			resolveNeonGlassIdentityColor(authoredBlack, luminousFallback),
		)
	}

	@Test
	fun `neutral custom accent preserves its authored value`() {
		val authoredGray = 0xFF606060.toInt()
		val luminousFallback = 0xFFFFFFFF.toInt()

		assertEquals(
			authoredGray,
			resolveNeonGlassIdentityColor(authoredGray, luminousFallback),
		)
	}

	@Test
	fun `chromatic custom accent keeps existing luminous treatment`() {
		val authoredBlue = 0xFF0040FF.toInt()
		val luminousFallback = 0xFF50B4FF.toInt()

		val resolved = resolveNeonGlassIdentityColor(authoredBlue, luminousFallback)

		assertEquals(luminousFallback, resolved)
		assertNotEquals(authoredBlue, resolved)
	}

	@Test
	fun `non custom palette keeps existing luminous treatment`() {
		val luminousFallback = 0xFF50B4FF.toInt()

		assertEquals(
			luminousFallback,
			resolveNeonGlassIdentityColor(null, luminousFallback),
		)
	}
}
