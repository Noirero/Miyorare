package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SourcePackHandoffNoReloadRegressionTest {

	@Test
	fun `handoff does not clear published catalogue state`() {
		val source = productionSource()
		val body = source.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")
		assertFalse(body.contains("mangaList.value="))
		assertFalse(body.contains("listError.value="))
		assertFalse(body.contains("hasNextPage.value="))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListViewModel.kt")
		).replace(Regex("""\s+"""), "")
}
