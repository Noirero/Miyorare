package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffProviderRegressionTest {

	@Test
	fun `handoff requires Miyorare Tsuki provider`() {
		val source = productionSource()
		val body = source.substringAfter("privatefunpauseSourcePackListWorkForDetails(){")
			.substringBefore("overridefunonScrolledToEnd")
		assertTrue(body.contains("as?TsukiMangaSource?:return"))
		assertTrue(body.contains("source.plugin.provider==TsukiPluginProvider.MIYORARE"))
	}

	private fun productionSource(): String = (
		sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
			File("app/src/main/kotlin/org/koitharu/kotatsu/remotelist/ui/RemoteListFragment.kt"),
		).firstOrNull(File::isFile)?.readText() ?: error("Cannot find RemoteListFragment.kt")
		).replace(Regex("""\s+"""), "")
}
