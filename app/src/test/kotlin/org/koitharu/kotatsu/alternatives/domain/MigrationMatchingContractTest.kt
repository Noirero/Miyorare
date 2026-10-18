package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMatchingContractTest {

	@Test
	fun `background auto fix searches all sources and removes timing dependent selection`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")

		assertTrue(source.contains("SearchSourceMode.ALL_SOURCES"))
		assertTrue(source.contains("candidateMatcher.rank"))
		assertTrue(source.contains("candidateMatcher.assess"))
		assertFalse(source.contains("selectLastWithTimeout"))
		assertFalse(source.contains("runningFold"))
	}

	@Test
	fun `only ready matching state reaches lossless migration`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")

		val decision = source.indexOf("MigrationMatchState.READY")
		val review = source.indexOf("MigrationMatchState.NEEDS_REVIEW")
		val migration = source.indexOf("migrateUseCase(")
		assertTrue(decision >= 0)
		assertTrue(review > decision)
		assertTrue(migration > review)
		assertTrue(source.contains("lossless=true"))
	}

	@Test
	fun `manual alternatives keep user preference mode while cross-source id collisions remain discoverable`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")

		assertTrue(source.contains("SearchSourceMode.PINNED_ONLY"))
		assertTrue(source.contains("SearchSourceMode.PREFERRED_LANGUAGES"))
		assertTrue(source.contains("SearchSourceMode.ALL_SOURCES"))
		assertTrue(source.contains("it.source!=manga.source||it.id!=manga.id"))
		assertFalse(source.contains("filter{it.id!=manga.id}"))
	}

	private fun source(relativePath: String): String {
		var dir = File(System.getProperty("user.dir"))
		repeat(8) {
			val candidate = File(dir, "src/main/kotlin/$relativePath")
			if (candidate.isFile) return candidate.readText().filterNot(Char::isWhitespace)
			dir = dir.parentFile ?: return@repeat
		}
		error("Cannot locate source file: $relativePath")
	}
}
