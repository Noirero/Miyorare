package org.koitharu.kotatsu.history.ui.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainbowReadingProgressRegressionTest {

	@Test
	fun `rainbow is scoped to modern filled progress without animation`() {
		val source = File(
			"src/main/kotlin/org/koitharu/kotatsu/history/ui/util/ReadingProgressView.kt",
		).readText()
		val modernDraw = source.substringAfter("private fun drawModernBottomEdge")
		assertTrue(modernDraw.contains("canvas.drawRoundRect(rectF, radius, radius, trackPaint)"))
		assertTrue(modernDraw.contains("val fillRight = width * value.percent.coerceIn(0f, 1f)"))
		assertTrue(modernDraw.contains("LinearGradient("))
		assertTrue(modernDraw.contains("RAINBOW_COLORS"))
		assertTrue(modernDraw.contains("rectF.right = fillRight"))
		assertTrue(modernDraw.contains("progressPaint.shader = null"))
		assertFalse(modernDraw.contains("ValueAnimator"))
	}
}
