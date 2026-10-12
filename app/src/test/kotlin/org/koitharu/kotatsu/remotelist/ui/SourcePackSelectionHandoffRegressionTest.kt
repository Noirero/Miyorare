package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackSelectionHandoffRegressionTest {

	@Test
	fun `selection handling remains ahead of navigation handoff`() {
		val base = source("org/koitharu/kotatsu/list/ui/MangaListFragment.kt")
		assertTrue(base.indexOf("selectionController?.onItemClick(item.id)") < base.indexOf("router.openDetails(manga)"))
	}

	private fun source(relativePath: String): String = (
		sequenceOf(File("src/main/kotlin", relativePath), File("app/src/main/kotlin", relativePath))
			.firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath")
		).replace(Regex("""\s+"""), "")
}
