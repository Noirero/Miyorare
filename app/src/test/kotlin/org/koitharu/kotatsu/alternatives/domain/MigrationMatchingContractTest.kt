package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMatchingContractTest {

	@Test
	fun `background auto fix keeps strict execution validation`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")

		assertTrue(autoFix.contains("MigrationMatchState.READY"))
		assertTrue(autoFix.contains("MigrationSelection.of(replacement)"))
		assertTrue(autoFix.contains("migrateSelected("))
		assertFalse(autoFix.contains("selectLastWithTimeout"))
		assertFalse(autoFix.contains("runningFold"))
	}

	@Test
	fun `selected migration refreshes approved destination before lossless execution`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val migrateSelected = autoFix.substringAfter("suspendfunmigrateSelected(").substringBefore("privatesuspendfunvalidateSelection(")
		val validation = autoFix.substringAfter("privatesuspendfunvalidateSelection(").substringBefore("privatesuspendfunloadStoredSeed(")

		assertTrue(migrateSelected.contains("validateSelection(mangaId,selection,allowReadableSource)"))
		assertTrue(migrateSelected.contains("selection.isRawSearchIdentity"))
		assertTrue(migrateSelected.contains("MigrationSelection::of"))
		assertTrue(migrateSelected.contains("lossless=true,requireLibraryEntry=true"))
		assertFalse(migrateSelected.contains("discover(mangaId)"))

		assertTrue(validation.contains("if(allowReadableSource)"))
		assertTrue(validation.contains("MigrationDiscovery(seed=loadStoredSeed(mangaId),sourceHealthy=false)"))
		assertTrue(validation.contains("checkCurrentSource(mangaId)"))
		assertTrue(validation.contains("if(currentReadable&&!allowReadableSource)returncurrent"))
		assertFalse(validation.contains("seed.isReadable()"))
		assertFalse(validation.contains("discover(mangaId)"))
		assertTrue(validation.contains("pool.allSources.firstOrNull{it.name==selection.source}"))
		assertTrue(validation.contains("refreshExactCandidate(query,selectedSource,selection.id)"))
		assertTrue(validation.contains("approved.manga.probeHealth()"))
		assertTrue(validation.contains("!selection.isRawSearchIdentity&&MigrationSelection.of(fresh)!=selection"))
		assertTrue(validation.contains("candidateMatcher.assess"))
		assertFalse(validation.contains("take(MAX_MIGRATION_DETAIL_CANDIDATES)"))
	}

	@Test
	fun `direct discovery searches only explicitly selected sources and keeps detail work deferred`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val direct = autoFix.substringAfter("privatesuspendfunsearchDirectReplacements(")
			.substringBefore("/**Strictchecked-modediscovery")
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")
		val rawSearch = alternatives.substringAfter("funsearchInstalledSourcesDirect(")
			.substringBefore("suspendfunrefreshExactCandidate(")

		assertTrue(direct.contains("selectedSourceNames?.let(pool::selected)"))
		assertTrue(direct.contains("buildDirectMigrationSearchQueries(seed,options)"))
		assertTrue(direct.contains("searchInstalledSourcesDirect("))
		assertFalse(direct.contains("probeHealth()"))
		assertFalse(direct.contains("VERIFYING_CANDIDATES"))
		assertTrue(rawSearch.contains("for(queryinnormalizedQueries)"))
		assertTrue(rawSearch.contains("if(results.isNotEmpty())"))
		assertFalse(rawSearch.contains("getDetails("))
		assertTrue(rawSearch.contains("SearchKind.TITLE"))
	}

	@Test
	fun `checked discovery remains strict and can also honor explicit source choices`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val strict = autoFix.substringAfter("privatesuspendfunsearchStrictReplacements(")
			.substringBefore("/**Refreshtheapproveddestinationidentity")
		val discover = autoFix.substringAfter("suspendfundiscover(").substringBefore("/**[directSearch]selects")

		assertTrue(discover.contains("directSearch=false"))
		assertTrue(strict.contains("selectedSourceNames?.let(pool::selected)"))
		assertTrue(strict.contains("buildMigrationSearchQueries(seed)"))
		assertTrue(strict.contains("VERIFYING_CANDIDATES"))
		assertTrue(strict.contains("probeHealth()"))
		assertTrue(strict.contains("MAX_HEALTH_CHECKS"))
	}

	@Test
	fun `direct UI prepares a source picker and never auto starts all-source search`() {
		val viewModel = source("org/koitharu/kotatsu/settings/sources/migration/MigrationReviewViewModel.kt")
		val manual = viewModel.substringAfter("funenableManualSearch()").substringBefore("funreloadSearchSources()")
		val scope = viewModel.substringAfter("funsetSearchScope(").substringBefore("funsetSourcesSelected(")
		val search = viewModel.substringAfter("funsearch()").substringBefore("privatefunstartSearch(")
		val start = viewModel.substringAfter("privatefunstartSearch(").substringBefore("funbackToSourceSelection(")

		assertTrue(manual.contains("prepareSourceSelection(directSearch=true)"))
		assertFalse(manual.contains("startSearch("))
		assertFalse(manual.contains("searchAvailability("))
		assertFalse(scope.contains("startSearch("))
		assertTrue(search.contains("before.selectedSourceNames.isEmpty()"))
		assertTrue(start.contains("selectedSourceNames=selectedSourceNames"))
		assertTrue(start.contains("directOptions=options"))
	}

	@Test
	fun `source picker uses installed source pool and selection is authoritative`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val scope = source("org/koitharu/kotatsu/alternatives/domain/MigrationSearchScope.kt")

		assertTrue(autoFix.contains("loadSearchSourcePool(mangaId:Long)"))
		assertTrue(scope.contains("funselected(sourceNames:Set<String>)"))
		assertTrue(scope.contains("allSources.filter{it.nameinsourceNames}"))
	}

	@Test
	fun `primary discovery query strips lightweight title decorators while aliases stay fallback`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val primary = autoFix.substringAfter("internalfunbuildPrimaryMigrationSearchQuery(").substringBefore("internalfunbuildDirectMigrationSearchQueries(")
		val directQueries = autoFix.substringAfter("internalfunbuildDirectMigrationSearchQueries(").substringBefore("internalfunbuildMigrationSearchQueries(")

		assertTrue(primary.contains("migrationTitleVariants(seed.title).lastOrNull()"))
		assertTrue(primary.contains("TRAILING_MIGRATION_DECORATOR"))
		assertTrue(directQueries.contains("options.titleMode"))
		assertTrue(directQueries.contains("options.searchAliasesIfNeeded"))
		assertTrue(directQueries.contains("options.includeAlternativeTitles"))
	}

	@Test
	fun `optional current source check is bounded and probes the newest normalized chapter`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val check = autoFix.substringAfter("suspendfuncheckCurrentSource(").substringBefore("/**Compatibility/counthelper")
		val probe = autoFix.substringAfter("privatesuspendfunManga.probeHealth()")

		assertTrue(check.contains("withTimeoutOrNull(CURRENT_SOURCE_CHECK_TIMEOUT_MS)"))
		assertTrue(autoFix.contains("CURRENT_SOURCE_CHECK_TIMEOUT_MS=12_000L"))
		assertTrue(probe.contains("details.chapters?.lastOrNull()"))
		assertFalse(probe.contains("details.chapters?.firstOrNull()"))
	}

	@Test
	fun `manual alternatives keep user preference mode while cross-source id collisions remain discoverable`() {
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")

		assertTrue(alternatives.contains("SearchSourceMode.PINNED_ONLY"))
		assertTrue(alternatives.contains("SearchSourceMode.PREFERRED_LANGUAGES"))
		assertTrue(alternatives.contains("SearchSourceMode.ALL_SOURCES"))
		assertTrue(alternatives.contains("searchSources(normalizedQuery,sources,manga.sourcetomanga.id,maxDetailCandidates)"))
		assertTrue(alternatives.contains("excluded==null||it.source!=excluded.first||it.id!=excluded.second"))
		assertFalse(alternatives.contains("filter{it.id!=manga.id}"))
	}

	@Test
	fun `legacy fix entry points route through explicit migration UI`() {
		val service = source("org/koitharu/kotatsu/alternatives/ui/AutoFixService.kt")
		val entryPoints = service.substringAfter("companionobject{")

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
