package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffStateOwnerRegressionTest {

	@Test
	fun `catalogue producer remains owned by loading job`() {
		val source = productionSource()
		assertTrue(source.contains("privatevarloadingJob:Job?=null"))
		assertTrue(source.contains(".also{loadingJob=it}"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListViewModel.kt")
		).replace(Regex("""\s+"""), "")
}
