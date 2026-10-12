package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackDirectNavigationRegressionTest {

	@Test
	fun `direct click releases Source Pack list producer before delegating navigation`() {
		val source = productionSource()
		val body = source.substringAfter("overridefunonItemClick(item:MangaListModel,view:View){")
			.substringBefore("privatefunpauseSourcePackListWorkForDetails")
		assertTrue(body.indexOf("pauseSourcePackListWorkForDetails()") in 0 until body.indexOf("super.onItemClick(item,view)"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListFragment.kt")
		).replace(Regex("""\s+"""), "")
}
