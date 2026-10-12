package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffLoadingJobRegressionTest {

	@Test
	fun `handoff clears cancelled loading job reference`() {
		val source = productionSource()
		val body = source.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")
		assertTrue(body.indexOf("loadingJob?.cancel()") < body.indexOf("loadingJob=null"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListViewModel.kt")
		).replace(Regex("""\s+"""), "")
}
