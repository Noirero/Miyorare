package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackNavigationOwnershipTest {

	@Test
	fun `direct and random Details navigation both release Source Pack catalogue work first`() {
		val source = productionSource()
		val direct = source.substringAfter("overridefunonItemClick(item:MangaListModel,view:View){").substringBefore("privatefunpauseSourcePackListWorkForDetails")
		val random = source.substringAfter("viewModel.onOpenManga.observeEvent(viewLifecycleOwner){").substringBefore("viewModel.onBrokenSortFallback")

		assertTrue(direct.indexOf("pauseSourcePackListWorkForDetails()") < direct.indexOf("super.onItemClick(item,view)"))
		assertTrue(random.indexOf("pauseSourcePackListWorkForDetails()") < random.indexOf("router.openDetails(it)"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find RemoteListFragment.kt")
		)
		.replace(Regex("""//[^\r\n]*"""), "")
		.replace(Regex("""\s+"""), "")
}
