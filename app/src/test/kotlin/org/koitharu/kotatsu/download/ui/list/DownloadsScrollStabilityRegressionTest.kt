package org.koitharu.kotatsu.download.ui.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadsScrollStabilityRegressionTest {

	@Test
	fun `collapsed download rows stay off chapter hydration hot path`() {
		val item = source("kotlin/org/koitharu/kotatsu/download/ui/list/DownloadItemAD.kt")
			.replace(Regex("\\s+"), "")

		val expandedGate = item.indexOf("if(item.isExpanded){")
		val chapterCollect = item.indexOf("item.chapters.collect{chapters->")
		assertTrue(expandedGate >= 0 && chapterCollect > expandedGate)
		assertTrue(item.contains("else{chaptersJob?.cancel()chaptersJob=null}"))
		assertTrue(item.contains("binding.buttonExpand.isGone=false"))
		assertFalse(item.contains("binding.buttonExpand.isGone=chapters.isNullOrEmpty()"))
	}

	@Test
	fun `live progress updates do not animate or bounce the outer list`() {
		val activity = source("kotlin/org/koitharu/kotatsu/download/ui/list/DownloadsActivity.kt")
			.replace(Regex("\\s+"), "")
		val layout = source("res/layout/activity_downloads.xml")

		assertTrue(activity.contains("itemAnimator=null"))
		assertFalse(layout.contains("scroll|enterAlways"))
		assertFalse(layout.contains("scroll|exitUntilCollapsed|snap"))
		assertTrue(layout.contains("app:layout_scrollFlags=\"scroll|exitUntilCollapsed\""))
		assertTrue(layout.contains("app:layout_scrollFlags=\"scroll\""))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
