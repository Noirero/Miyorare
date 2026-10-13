package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaListActivityResumeLifecycleTest {

	@Test
	fun `resolved Mihon source is ignored after activity leaves resumed state`() {
		val onResume = onResumeSource()
		val staleRefreshGuard = onResume.indexOf("if(source!==staleSource)return@launch")
		val lifecycleGuard = onResume.indexOf(
			"if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)||supportFragmentManager.isStateSaved){return@launch}",
		)
		val sourceMutation = onResume.indexOf("source=resolved.source")
		val fragmentReload = onResume.indexOf("reloadList(source)")

		assertTrue("stale refresh guard must remain present", staleRefreshGuard >= 0)
		assertTrue("lifecycle/state-saved guard must remain present", lifecycleGuard > staleRefreshGuard)
		assertTrue("source must not mutate before the lifecycle guard", sourceMutation > lifecycleGuard)
		assertTrue("fragment reload must not run before the lifecycle guard", fragmentReload > lifecycleGuard)
	}

	private fun onResumeSource(): String {
		val relativePath = "org/koitharu/kotatsu/search/ui/MangaListActivity.kt"
		val source = sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production source: $relativePath")

		return source
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
			.substringAfter("overridefunonResume(){")
			.substringBefore("/**")
	}
}
