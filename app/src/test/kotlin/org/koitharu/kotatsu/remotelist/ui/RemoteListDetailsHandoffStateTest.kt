package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteListDetailsHandoffStateTest {

	@Test
	fun `handoff cancels only active catalogue producer and retains published state`() {
		val source = productionSource()
		val body = source.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")

		assertTrue(body.contains("loadingJob?.cancel()"))
		assertTrue(body.contains("loadingJob=null"))
		assertFalse(body.contains("mangaList.value="))
		assertFalse(body.contains("hasNextPage.value="))
		assertFalse(body.contains("listError.value="))
		assertFalse(body.contains("randomJob"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt"),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find RemoteListViewModel.kt")
		)
		.replace(Regex("""//[^\r\n]*"""), "")
		.replace(Regex("""\s+"""), "")
}
