package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffOrderingRegressionTest {

	@Test
	fun `catalogue cancellation is requested before direct Details delegation`() {
		val source = productionSource()
		val click = source.substringAfter("overridefunonItemClick(item:MangaListModel,view:View){")
			.substringBefore("privatefunpauseSourcePackListWorkForDetails")
		assertTrue(click.indexOf("pauseSourcePackListWorkForDetails()") < click.indexOf("super.onItemClick(item,view)"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListFragment.kt")
		).replace(Regex("""\s+"""), "")
}
