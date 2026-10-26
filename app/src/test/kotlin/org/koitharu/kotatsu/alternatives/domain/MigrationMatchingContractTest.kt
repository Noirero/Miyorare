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
	fun `selected migration refreshes only approved destination identity before lossless execution`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val migrateSelected = source.substringAfter("suspendfunmigrateSelected(").substringBefore("privatesuspendfunvalidateSelection(")
		val validation = source.substringAfter("privatesuspendfunvalidateSelection(").substringBefore("privatesuspendfunloadStoredSeed(")

		assertTrue(migrateSelected.contains("validateSelection(mangaId,selection,allowReadableSource)"))
		assertTrue(migrateSelected.contains("allowReadableSource:Boolean=false"))
		assertFalse(migrateSelected.contains("discover(mangaId)"))
		assertTrue(migrateSelected.contains("lossless=true,requireLibraryEntry=true"))

		// Direct-search mode intentionally skips the old source. Checked mode reuses the bounded
		// current-source check. Neither path is allowed to run full replacement discovery again.
		assertTrue(validation.contains("if(allowReadableSource)"))
		assertTrue(validation.contains("MigrationDiscovery(seed=loadStoredSeed(mangaId),sourceHealthy=false)"))
		assertTrue(validation.contains("checkCurrentSource(mangaId)"))
		assertTrue(validation.contains("currentReadable=current.currentSourceReadable"))
		assertTrue(validation.contains("if(currentReadable&&!allowReadableSource)returncurrent"))
		assertFalse(validation.contains("seed.isReadable()"))
		assertFalse(validation.contains("discover(mangaId)"))

		// The approved destination still has to be found again from its exact source, health-probed,
		// fingerprint-checked and reassessed before the lossless transfer receives permission.
		assertTrue(validation.contains("filter{it.name==selection.source}"))
		assertTrue(validation.contains("precomputedSources=selectedSources"))
		assertTrue(validation.contains("event.manga.source.name==selection.source&&event.manga.id==selection.id"))
		assertFalse(validation.contains("MigrationSelection.of(event.manga)==selection"))
		assertTrue(validation.contains("if(selected!=null)break"))
		assertTrue(validation.contains("approved.manga.probeHealth()"))
		assertTrue(validation.contains("MigrationSelection.of(fresh)!=selection"))
		assertTrue(validation.contains("candidateMatcher.assess"))
	}

	@Test
	fun `direct replacement discovery never opens the old source`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val availability = source.substringAfter("suspendfunsearchAvailability(").substringBefore("suspendfundiscover(")
		val directSearch = source.substringAfter("suspendfunsearchReplacements(").substringBefore("/**Refreshtheapproveddestinationidentity")

		assertTrue(availability.contains("loadStoredSeed(mangaId)"))
		assertFalse(availability.contains("getDetailsSafe()"))
		assertFalse(availability.contains("checkCurrentSource("))
		assertTrue(directSearch.contains("val seed=loadStoredSeed(mangaId)"))
		assertFalse(directSearch.contains("getDetailsSafe()"))
		assertFalse(directSearch.contains("checkCurrentSource("))
	}

	@Test
	fun `optional current source check is bounded and probes the newest normalized chapter`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val check = source.substringAfter("suspendfuncheckCurrentSource(").substringBefore("/**Directreplacementsearch")
		val probe = source.substringAfter("privatesuspendfunManga.probeHealth()")

		assertTrue(check.contains("withTimeoutOrNull(CURRENT_SOURCE_CHECK_TIMEOUT_MS)"))
		assertTrue(source.contains("CURRENT_SOURCE_CHECK_TIMEOUT_MS=12_000L"))
		assertTrue(probe.contains("details.chapters?.lastOrNull()"))
		assertFalse(probe.contains("details.chapters?.firstOrNull()"))
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
