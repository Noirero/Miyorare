package org.koitharu.kotatsu.core.ui

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MiyorareNeonGlassIdentityTest {

	@Test
	fun `explicit black custom accent remains black instead of luminous`() {
		val luminousFallback = Color.WHITE

		assertEquals(
			Color.BLACK,
			resolveNeonGlassIdentityColor(Color.BLACK, luminousFallback),
		)
	}

	@Test
	fun `neutral custom accent preserves its authored value`() {
		val authoredGray = Color.rgb(96, 96, 96)
		val luminousFallback = Color.WHITE

		assertEquals(
			authoredGray,
			resolveNeonGlassIdentityColor(authoredGray, luminousFallback),
		)
	}

	@Test
	fun `chromatic custom accent keeps existing luminous treatment`() {
		val authoredBlue = Color.rgb(0, 64, 255)
		val luminousFallback = Color.rgb(80, 180, 255)

		val resolved = resolveNeonGlassIdentityColor(authoredBlue, luminousFallback)

		assertEquals(luminousFallback, resolved)
		assertNotEquals(authoredBlue, resolved)
	}

	@Test
	fun `non custom palette keeps existing luminous treatment`() {
		val luminousFallback = Color.rgb(80, 180, 255)

		assertEquals(
			luminousFallback,
			resolveNeonGlassIdentityColor(null, luminousFallback),
		)
	}
}
