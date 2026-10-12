package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffCancellationRegressionTest {

	@Test
	fun `handoff requests cancellation without waiting on UI thread`() {
		val source = productionSource()
		val body = source.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")
		assertTrue(body.contains("loadingJob?.cancel()"))
		assertTrue(!body.contains("cancelAndJoin"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListViewModel.kt")
		).replace(Regex("""\s+"""), "")
}
