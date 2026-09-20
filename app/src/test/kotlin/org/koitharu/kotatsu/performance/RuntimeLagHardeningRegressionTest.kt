package org.koitharu.kotatsu.performance

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLagHardeningRegressionTest {

	@Test
	fun `reader prefetch cannot occupy every foreground page-load slot`() {
		val source = source("kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("privatevalsemaphore=Semaphore(4)"))
		assertTrue(source.contains("privatevalprefetchSemaphore=Semaphore(PREFETCH_MAX_PARALLELISM)"))
		assertTrue(source.contains("privateconstvalPREFETCH_MAX_PARALLELISM=2"))
		assertTrue(source.contains("if(isPrefetch){prefetchSemaphore.withPermit{"))
		assertTrue(source.contains("loadPageWithPermit(page,progress,isPrefetch=true,skipCache=skipCache)"))
	}

	@Test
	fun `adaptive update reuses one source-health snapshot per source in a selection window`() {
		val source = source("kotlin/org/koitharu/kotatsu/tracker/domain/SmartUpdatePolicy.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("valsourceHealth=HashMap<String,SourceHealthRepository.State>()"))
		assertTrue(source.contains("sourceHealth.getOrPut(tracking.manga.source.name)"))
		assertTrue(source.contains("sourceHealthRepository.snapshotForScheduling(tracking.manga.source).state"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
