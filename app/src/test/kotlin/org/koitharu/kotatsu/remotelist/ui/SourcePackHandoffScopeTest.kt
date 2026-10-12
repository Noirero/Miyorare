package org.koitharu.kotatsu.remotelist.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePackHandoffScopeTest {

	@Test
	fun `handoff does not broaden to other extension families`() {
		val source = productionSource()
		val body = source.substringAfter("privatefunpauseSourcePackListWorkForDetails(){").substringBefore("overridefunonScrolledToEnd")

		assertTrue(body.contains("TsukiMangaSource"))
		assertTrue(body.contains("TsukiPluginProvider.MIYORARE"))
		assertFalse(body.contains("MihonMangaSource"))
		assertFalse(body.contains("LnMangaSource"))
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
