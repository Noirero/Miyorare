package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackRandomNavigationRegressionTest {

	@Test
	fun `random result releases Source Pack list producer before Details open`() {
		val source = productionSource()
		val body = source.substringAfter("viewModel.onOpenManga.observeEvent(viewLifecycleOwner){")
			.substringBefore("viewModel.onBrokenSortFallback")
		assertTrue(body.indexOf("pauseSourcePackListWorkForDetails()") in 0 until body.indexOf("router.openDetails(it)"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListFragment.kt")
		).replace(Regex("""\s+"""), "")
}
