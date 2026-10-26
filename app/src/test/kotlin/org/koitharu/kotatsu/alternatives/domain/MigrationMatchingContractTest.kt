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
	fun `selected migration refreshes only approved identity before lossless execution`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val migrateSelected = source.substringAfter("suspendfunmigrateSelected(").substringBefore("privatesuspendfunvalidateSelection(")
		val validation = source.substringAfter("privatesuspendfunvalidateSelection(").substringBefore("privatesuspendfunloadStoredSeed(")

		assertTrue(migrateSelected.contains("validateSelection(mangaId,selection,allowReadableSource)"))
		assertTrue(migrateSelected.contains("allowReadableSource:Boolean=false"))
		assertFalse(migrateSelected.contains("discover(mangaId)"))
		assertTrue(migrateSelected.contains("lossless=true,requireLibraryEntry=true"))
		assertTrue(validation.contains("currentReadable=seed.isReadable()"))
		assertTrue(validation.contains("if(currentReadable&&!allowReadableSource)returnMigrationDiscovery(seed,sourceHealthy=true)"))
		assertTrue(validation.contains("filter{it.name==selection.source}"))
		assertTrue(validation.contains("precomputedSources=selectedSources"))
		assertTrue(validation.contains("event.manga.source.name==selection.source&&event.manga.id==selection.id"))
		assertFalse(validation.contains("MigrationSelection.of(event.manga)==selection"))
		assertTrue(validation.contains("if(selected!=null)break"))
		assertTrue(validation.contains("MigrationSelection.of(fresh)!=selection"))
		assertTrue(validation.contains("candidateMatcher.assess"))
		assertFalse(validation.contains("discover(mangaId)"))
	}

	@Test
	fun `manual alternatives keep user preference mode while cross-source id collisions remain discoverable`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")

		assertTrue(source.contains("SearchSourceMode.PINNED_ONLY"))
		assertTrue(source.contains("SearchSourceMode.PREFERRED_LANGUAGES"))
		assertTrue(source.contains("SearchSourceMode.ALL_SOURCES"))
		assertTrue(source.contains("searchSources(normalizedQuery,sources,manga.sourcetomanga.id,maxDetailCandidates)"))
		assertTrue(source.contains("excluded==null||it.source!=excluded.first||it.id!=excluded.second"))
		assertFalse(source.contains("filter{it.id!=manga.id}"))
	}

	@Test
	fun `legacy fix entry points route through explicit migration UI`() {
		val source = source("org/koitharu/kotatsu/alternatives/ui/AutoFixService.kt")
		val entryPoints = source.substringAfter("companionobject{")

		assertTrue(entryPoints.contains("ACTION_MIGRATION_REVIEW"))
		assertTrue(entryPoints.contains("ACTION_MIGRATION"))
		assertTrue(entryPoints.contains("context.startActivity("))
		assertFalse(entryPoints.contains("startForegroundService"))
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
