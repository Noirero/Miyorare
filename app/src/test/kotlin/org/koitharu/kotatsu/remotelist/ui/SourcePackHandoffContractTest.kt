package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffContractTest {

	@Test
	fun `handoff preserves catalogue state and is limited to Miyorare Tsuki`() {
		val fragment = source("org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt")
		val viewModel = source("org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt")
		val handoff = viewModel.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")

		assertTrue(fragment.contains("TsukiPluginProvider.MIYORARE"))
		assertTrue(fragment.contains("viewModel.pauseListWorkForDetailsHandoff()"))
		assertTrue(handoff.contains("loadingJob?.cancel()"))
		assertFalse(handoff.contains("mangaList.value="))
		assertFalse(handoff.contains("hasNextPage.value="))
		assertFalse(handoff.contains("randomJob"))
	}

	private fun source(relativePath: String): String = (
		sequenceOf(File("src/main/kotlin", relativePath), File("app/src/main/kotlin", relativePath))
			.firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath")
		).replace(Regex("""//[^\r\n]*"""), "").replace(Regex("""\s+"""), "")
}
