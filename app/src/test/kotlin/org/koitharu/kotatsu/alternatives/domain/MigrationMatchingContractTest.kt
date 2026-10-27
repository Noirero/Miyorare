package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMatchingContractTest {

	@Test
	fun `background auto fix keeps strict execution validation`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")

		assertTrue(source.contains("MigrationMatchState.READY"))
		assertTrue(source.contains("MigrationSelection.fromSearchResult(replacement)"))
		assertTrue(source.contains("migrateSelected("))
		assertFalse(source.contains("selectLastWithTimeout"))
		assertFalse(source.contains("runningFold"))
	}

	@Test
	fun `selected migration refreshes approved destination before lossless execution`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val migrateSelected = source.substringAfter("suspendfunmigrateSelected(").substringBefore("privatesuspendfunvalidateSelection(")
		val validation = source.substringAfter("privatesuspendfunvalidateSelection(").substringBefore("privatesuspendfunloadStoredSeed(")

		assertTrue(migrateSelected.contains("validateSelection(mangaId,selection,allowReadableSource)"))
		assertTrue(migrateSelected.contains("selection.isRawSearchIdentity"))
		assertTrue(migrateSelected.contains("MigrationSelection::of"))
		assertTrue(migrateSelected.contains("lossless=true,requireLibraryEntry=true"))
		assertFalse(migrateSelected.contains("discover(mangaId)"))

		// Direct mode skips the old source. Checked mode still keeps the bounded current-source guard.
		assertTrue(validation.contains("if(allowReadableSource)"))
		assertTrue(validation.contains("MigrationDiscovery(seed=loadStoredSeed(mangaId),sourceHealthy=false)"))
		assertTrue(validation.contains("checkCurrentSource(mangaId)"))
		assertTrue(validation.contains("if(currentReadable&&!allowReadableSource)returncurrent"))
		assertFalse(validation.contains("seed.isReadable()"))
		assertFalse(validation.contains("discover(mangaId)"))

		// The exact destination source/id is searched again, then details/page health and the fresh
		// full fingerprint are established before lossless transfer can proceed.
		assertTrue(validation.contains("filter{it.name==selection.source}"))
		assertTrue(validation.contains("precomputedSources=selectedSources"))
		assertTrue(validation.contains("event.manga.source.name==selection.source&&event.manga.id==selection.id"))
		assertTrue(validation.contains("if(selected!=null)break"))
		assertTrue(validation.contains("approved.manga.probeHealth()"))
		assertTrue(validation.contains("!selection.isRawSearchIdentity&&MigrationSelection.of(fresh)!=selection"))
		assertTrue(validation.contains("candidateMatcher.assess"))
	}

	@Test
	fun `direct discovery is one raw query per installed source with no preflight health probe`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val direct = source.substringAfter("suspendfunsearchReplacements(").substringBefore("/**Refreshtheapproveddestinationidentity")
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")
		val rawSearch = alternatives.substringAfter("funsearchInstalledSourcesDirect(").substringBefore("privatefunsearchSources(")

		assertTrue(direct.contains("buildPrimaryMigrationSearchQuery(seed)"))
		assertTrue(direct.contains("searchInstalledSourcesDirect("))
		assertTrue(direct.contains("queryIndex=1"))
		assertTrue(direct.contains("queryTotal=1"))
		assertFalse(direct.contains("buildMigrationSearchQueries(seed)"))
		assertFalse(direct.contains("probeHealth()"))
		assertFalse(direct.contains("VERIFYING_CANDIDATES"))
		assertFalse(rawSearch.contains("getDetails("))
		assertTrue(rawSearch.contains("SearchKind.TITLE"))
		assertTrue(rawSearch.contains("SourceFinished(source,error)"))
		assertTrue(rawSearch.contains("SourceFinished(source,null)"))
	}

	@Test
	fun `direct UI starts search immediately and scope changes restart it without availability gate`() {
		val viewModel = source("org/koitharu/kotatsu/settings/sources/migration/MigrationReviewViewModel.kt")
		val manual = viewModel.substringAfter("funenableManualSearch()").substringBefore("funsetSearchScope(")
		val scope = viewModel.substringAfter("funsetSearchScope(").substringBefore("funsearch()")

		assertTrue(manual.contains("startSearch(before.selectedScope,revealAllCandidates=true)"))
		assertFalse(manual.contains("searchAvailability("))
		assertTrue(scope.contains("if(before.manualSearchEnabled)"))
		assertTrue(scope.contains("startSearch(scope,revealAllCandidates=true)"))
		assertFalse(viewModel.contains("availability?.count(before.selectedScope)"))
	}

	@Test
	fun `primary discovery query strips lightweight title decorators while aliases stay execution fallback`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val primary = source.substringAfter("internalfunbuildPrimaryMigrationSearchQuery(").substringBefore("internalfunbuildMigrationSearchQueries(")
		val fallback = source.substringAfter("internalfunbuildMigrationSearchQueries(")

		assertTrue(primary.contains("migrationTitleVariants(seed.title).lastOrNull()"))
		assertTrue(primary.contains("TRAILING_MIGRATION_DECORATOR"))
		assertTrue(fallback.contains("addQuery(buildPrimaryMigrationSearchQuery(seed))"))
		assertTrue(fallback.contains("seed.altTitles"))
	}

	@Test
	fun `optional current source check is bounded and probes the newest normalized chapter`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val check = source.substringAfter("suspendfuncheckCurrentSource(").substringBefore("suspendfunsearchAvailability(")
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
