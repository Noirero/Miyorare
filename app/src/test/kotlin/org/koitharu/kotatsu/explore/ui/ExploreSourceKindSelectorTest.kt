package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.explore.ui.adapter.sourceIconInsetPx
import java.io.File

class ExploreSourceKindSelectorTest {

	@Test
	fun `source kind selector fills the header gap with two equal tabs`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""android:id="@+id/tabs_kind""""))
		assertTrue(layout.contains("""android:layout_width="0dp""""))
		assertTrue(layout.contains("""android:layout_weight="1""""))
		assertTrue(layout.contains("""app:tabGravity="fill""""))
		assertTrue(layout.contains("""app:tabMode="fixed""""))
		assertFalse(layout.contains("ChipGroup"))
		assertFalse(layout.contains("chip_kind_manga"))
		assertFalse(layout.contains("chip_kind_novel"))
	}

	@Test
	fun `source kind selector has no tap highlight`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""app:tabRippleColor="@null""""))
	}

	@Test
	fun `source pages show loading while external source discovery is running`() {
		val source = source("org/koitharu/kotatsu/explore/ui/ExploreViewModel.kt")

		assertTrue(source.contains("isExtensionsLoading->result+=LoadingState"))
	}

	@Test
	fun `novel icons receive adaptive safe zone inset`() {
		assertEquals(8, sourceIconInsetPx(iconSizePx = 80, isNovel = true))
		assertEquals(4, sourceIconInsetPx(iconSizePx = 40, isNovel = true))
		assertEquals(0, sourceIconInsetPx(iconSizePx = 80, isNovel = false))
	}

	@Test
	fun `extensions action uses text and segmented rail proportions`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""android:text="@string/extensions""""))
		assertFalse(layout.contains("""app:icon="@drawable/ic_extension_manage""""))
		assertFalse(layout.contains("""android:layout_width="@dimen/explore_header_side_width""""))
		assertTrue(
			Regex(
				"""<FrameLayout[\\s\\S]*?android:layout_weight="2"[\\s\\S]*?<com.google.android.material.tabs.TabLayout[\\s\\S]*?android:id="@\\+id/tabs_kind"""",
			).containsMatchIn(layout),
		)
		assertTrue(
			Regex(
				"""<com.google.android.material.button.MaterialButton[\\s\\S]*?android:id="@\\+id/button_manage"[\\s\\S]*?android:layout_width="0dp"[\\s\\S]*?android:layout_weight="1"""",
			).containsMatchIn(layout),
		)
	}

	private fun layout(name: String): String {
		return sequenceOf(
			File("src/main/res/layout", name),
			File("app/src/main/res/layout", name),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production layout: $name")
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?.replace(Regex("""//[^\r\n]*"""), "")
			?.replace(Regex("""\s+"""), "")
			?: error("Cannot find production source: $relativePath")
	}
}
