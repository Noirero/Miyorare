package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffSourceScopeRegressionTest {

	@Test
	fun `handoff checks unwrapped Tsuki source`() {
		val source = productionSource()
		assertTrue(source.contains("viewModel.source.unwrap()as?TsukiMangaSource?:return"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListFragment.kt")
		).replace(Regex("""\s+"""), "")
}
