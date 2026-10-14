package org.koitharu.kotatsu.favourites.ui.list

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAcceptanceFollowupRegressionTest {

	@Test
	fun `local favourites opts into modern favourites renderer authority`() {
		val source = File(
			"src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/LocalFavouritesListFragment.kt",
		).readText()
		assertTrue(source.contains("gridVisualScaleProvider = { 1f }"))
	}

	@Test
	fun `chapter personal actions expose forty eight dp touch targets`() {
		val layout = File("src/main/res/layout/view_chapter_personal_indicators.xml").readText()
		assertEquals(2, Regex("android:layout_width=\"48dp\"").findAll(layout).count())
		assertEquals(2, Regex("android:layout_height=\"48dp\"").findAll(layout).count())
		assertTrue(layout.contains("android:id=\"@+id/imageView_personal_note\""))
		assertTrue(layout.contains("android:id=\"@+id/imageView_personal_star\""))
	}
}
