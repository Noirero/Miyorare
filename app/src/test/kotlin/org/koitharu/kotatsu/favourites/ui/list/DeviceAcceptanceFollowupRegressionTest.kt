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
	fun `local favourites enriches models with modern indicator semantics`() {
		val source = File(
			"src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/LocalFavouritesListViewModel.kt",
		).readText()
		assertTrue(source.contains("FavouriteUnreadCounter"))
		assertTrue(source.contains("isLocalSource = display.showLocalSource"))
		assertTrue(source.contains("languageLabel = languageLabel"))
		assertTrue(source.contains("showContinueReading = hasReadingHistory"))
		assertTrue(source.contains("counter = unreadCount"))
		assertTrue(source.contains("progress = progress"))
		assertTrue(source.contains("isSaved = model.isSaved && display.showDownloaded"))
	}

	@Test
	fun `chapter personal actions keep touch targets while star glyph hugs rating`() {
		val layout = File("src/main/res/layout/view_chapter_personal_indicators.xml").readText()
		assertEquals(2, Regex("android:layout_width=\"48dp\"").findAll(layout).count())
		assertEquals(2, Regex("android:layout_height=\"48dp\"").findAll(layout).count())
		assertTrue(layout.contains("android:id=\"@+id/imageView_personal_note\""))
		assertTrue(layout.contains("android:id=\"@+id/imageView_personal_star\""))
		assertTrue(layout.contains("android:paddingStart=\"2dp\""))
		assertTrue(layout.contains("android:paddingEnd=\"28dp\""))
	}

	@Test
	fun `modern reading progress uses static rainbow only for filled segment`() {
		val source = File(
			"src/main/kotlin/org/koitharu/kotatsu/history/ui/util/ReadingProgressView.kt",
		).readText()
		assertTrue(source.contains("val fillRight = width * value.percent.coerceIn(0f, 1f)"))
		assertTrue(source.contains("LinearGradient("))
		assertTrue(source.contains("fillRight,"))
		assertTrue(source.contains("RAINBOW_COLORS"))
		assertTrue(source.contains("canvas.drawRoundRect(rectF, radius, radius, trackPaint)"))
		assertTrue(source.contains("progressPaint.shader = null"))
	}
}
