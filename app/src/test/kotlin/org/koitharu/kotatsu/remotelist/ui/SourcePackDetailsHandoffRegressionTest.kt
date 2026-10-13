package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackDetailsHandoffRegressionTest {

	@Test
	fun `Miyorare Source Pack releases catalogue work before Details navigation`() {
		val fragment = source("org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt")
		val viewModel = source("org/koitharu/kotatsu/remotelist/ui/RemoteListViewModel.kt")

		val click = fragment.indexOf("overridefunonItemClick(item:MangaListModel,view:View)")
		val pause = fragment.indexOf("pauseSourcePackListWorkForDetails()", startIndex = click)
		val delegate = fragment.indexOf("super.onItemClick(item,view)", startIndex = pause)
		assertTrue(click >= 0)
		assertTrue(pause > click)
		assertTrue(delegate > pause)

		assertTrue(fragment.contains("source.plugin.provider==TsukiPluginProvider.MIYORARE"))
		assertTrue(fragment.contains("viewModel.pauseListWorkForDetailsHandoff()"))
		assertTrue(viewModel.contains("funpauseListWorkForDetailsHandoff(){loadingJob?.cancel()loadingJob=null}"))

		// The handoff must preserve already-published catalogue state so Back does not rebuild the list.
		val handoff = viewModel.substringAfter("funpauseListWorkForDetailsHandoff(){").substringBefore("}")
		assertFalse(handoff.contains("mangaList.value=null"))
		assertFalse(handoff.contains("hasNextPage.value=false"))
		assertFalse(handoff.contains("listError.value=null"))
	}

	@Test
	fun `handoff is scoped to Miyorare Tsuki Source Pack`() {
		val fragment = source("org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt")
		val handoff = fragment.substringAfter("privatefunpauseSourcePackListWorkForDetails(){").substringBefore("overridefunonScrolledToEnd")

		assertTrue(handoff.contains("as?TsukiMangaSource?:return"))
		assertTrue(handoff.contains("TsukiPluginProvider.MIYORARE"))
		assertFalse(handoff.contains("LnMangaSource"))
		assertFalse(handoff.contains("MihonMangaSource"))
	}

	private fun source(relativePath: String): String = (
		sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production source: $relativePath")
		)
		.replace(Regex("""//[^\r\n]*"""), "")
		.replace(Regex("""\s+"""), "")
}
