package org.koitharu.kotatsu.list.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaStatusCornerRegressionTest {

	@Test
	fun statusCornerUsesThemeAuthorityAndKeepsExistingStatusStateIds() {
		val layout = resource("layout/view_manga_indicators.xml")
		val container = resource("drawable/bg_manga_status_corner_container.xml")
		val corner = resource("drawable/bg_manga_status_corner.xml")

		assertTrue(layout.contains("android:id=\"@+id/status_ribbon\""))
		assertTrue(layout.contains("android:id=\"@+id/status_library\""))
		assertTrue(layout.contains("android:id=\"@+id/status_downloaded\""))
		assertTrue(layout.contains("android:background=\"@drawable/bg_manga_status_corner_container\""))
		assertFalse(layout.contains("android:background=\"@drawable/bg_manga_status_corner\""))
		assertTrue(layout.contains("android:tint=\"?attr/colorOnPrimary\""))
		assertFalse(layout.contains("android:background=\"?attr/colorTertiary\""))
		assertTrue(container.contains("android:width=\"28dp\""))
		assertTrue(container.contains("android:height=\"28dp\""))
		assertTrue(container.contains("android:gravity=\"top|end\""))
		assertTrue(container.contains("android:drawable=\"@drawable/bg_manga_status_corner\""))
		assertTrue(corner.contains("android:fillColor=\"?attr/colorPrimary\""))
		assertTrue(corner.contains("android:pathData=\"M0,0 L28,0 L28,28 Z\""))
	}

	private fun resource(path: String): String = sequenceOf(
		File("src/main/res", path),
		File("app/src/main/res", path),
	).first(File::isFile).readText()
}
