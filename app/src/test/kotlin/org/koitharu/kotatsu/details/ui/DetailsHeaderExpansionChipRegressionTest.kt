package org.koitharu.kotatsu.details.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsHeaderExpansionChipRegressionTest {

	@Test
	fun modernHeaderExpansionKeepsWiringAndUsesOutlinedChipPresentation() {
		val progress = source("main/kotlin/org/koitharu/kotatsu/details/ui/ProgressComponents.kt")
		val screen = source("main/kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt")

		assertTrue(progress.contains("showHeaderExpansion && onHeaderExpansionClick != null"))
		assertTrue(progress.contains("clickable(onClick = onHeaderExpansionClick)"))
		assertTrue(progress.contains("R.string.collapse else R.string.expand"))
		assertTrue(progress.contains("rotate(if (headerExpanded) -90f else 90f)"))
		assertTrue(progress.contains("RoundedCornerShape(50)"))
		assertTrue(progress.contains("BorderStroke(1.dp, progressAccent.copy(alpha = 0.72f))"))
		assertTrue(progress.contains("background(progressAccent.copy(alpha = 0.08f), headerExpansionShape)"))
		assertTrue(screen.contains("showHeaderExpansion = showHeroExpansion"))
		assertTrue(screen.contains("headerExpanded = heroExpanded"))
		assertTrue(screen.contains("onHeaderExpansionClick = { heroExpanded = !heroExpanded }"))
	}

	private fun source(path: String): String = sequenceOf(
		File("src", path),
		File("app/src", path),
	).first(File::isFile).readText()
}
