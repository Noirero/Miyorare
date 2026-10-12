package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SourcePackRandomStateRegressionTest {

	@Test
	fun `catalogue handoff does not cancel random request owner`() {
		val source = productionSource()
		val body = source.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")
		assertFalse(body.contains("randomJob"))
		assertFalse(body.contains("isRandomLoading"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListViewModel.kt")
		).replace(Regex("""\s+"""), "")
}
