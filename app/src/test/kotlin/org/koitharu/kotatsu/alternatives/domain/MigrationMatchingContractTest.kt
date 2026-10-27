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
	fun `selected migration refreshes exact destination before lossless execution`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")
		val migrateSelected = autoFix.substringAfter("suspendfunmigrateSelected(").substringBefore("privatesuspendfunvalidateSelection(")
		val validation = autoFix.substringAfter("privatesuspendfunvalidateSelection(").substringBefore("privatesuspendfunloadStoredSeed(")
		val exactRefresh = alternatives.substringAfter("suspendfunrefreshExactCandidate(").substringBefore("privatefunsearchSources(")

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
		assertTrue(validation.contains("firstOrNull{it.name==selection.source}"))
		assertTrue(validation.contains("refreshExactCandidate(query,selectedSource,selection.id)"))
		assertTrue(validation.contains("approved.manga.probeHealth()"))
		assertTrue(validation.contains("!selection.isRawSearchIdentity&&MigrationSelection.of(fresh)!=selection"))
		assertTrue(validation.contains("candidateMatcher.assess"))

		assertTrue(exactRefresh.contains("firstOrNull{it.source.name==source.name&&it.id==candidateId}"))
		assertTrue(exactRefresh.contains("getDetails(candidate)"))
		assertFalse(exactRefresh.contains("take("))
		assertFalse(exactRefresh.contains("MAX_DETAIL_CANDIDATES"))
	}

	@Test
	fun `direct discovery is one raw query per installed source with no preflight health probe`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val direct = autoFix.substringAfter("privatesuspendfunsearchDirectReplacements(")
			.substringBefore("/**Strictchecked-modediscovery")
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")
		val rawSearch = alternatives.substringAfter("funsearchInstalledSourcesDirect(").substringBefore("/**Execution-timedestinationrefresh")

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
	fun `checked discovery keeps alias search and health verification`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val strict = autoFix.substringAfter("privatesuspendfunsearchStrictReplacements(")
			.substringBefore("/**Refreshtheapproveddestinationidentity")
		val discover = autoFix.substringAfter("suspendfundiscover(").substringBefore("/**[directSearch]selects")

		assertTrue(discover.contains("directSearch=false"))
		assertTrue(strict.contains("buildMigrationSearchQueries(seed)"))
		assertTrue(strict.contains("VERIFYING_CANDIDATES"))
		assertTrue(strict.contains("probeHealth()"))
		assertTrue(strict.contains("MAX_HEALTH_CHECKS"))
	}

	@Test
	fun `direct UI starts search immediately and scope changes restart it without availability gate`() {
		val viewModel = source("org/koitharu/kotatsu/settings/sources/migration/MigrationReviewViewModel.kt")
		val manual = viewModel.substringAfter("funenableManualSearch()").substringBefore("funsetSearchScope(")
		val scope = viewModel.substringAfter("funsetSearchScope(").substringBefore("funsearch()")
		val start = viewModel.substringAfter("privatefunstartSearch(").substringBefore("funmigrate(")

		assertTrue(manual.contains("startSearch(before.selectedScope,directSearch=true)"))
		assertFalse(manual.contains("searchAvailability("))
		assertTrue(scope.contains("if(before.manualSearchEnabled)"))
		assertTrue(scope.contains("startSearch(scope,directSearch=true)"))
		assertTrue(start.contains("directSearch=directSearch"))
		assertTrue(start.contains("revealAllCandidates=directSearch"))
		assertFalse(viewModel.contains("availability?.count(before.selectedScope)"))
	}

	@Test
	fun `batch direct uses lightweight path while checked batch keeps discover`() {
		val worker = source("org/koitharu/kotatsu/alternatives/work/MigrationBatchWorker.kt")
		assertTrue(worker.contains("MigrationBatchDiscoveryMode.CHECK_CURRENT->autoFix.discover"))
		assertTrue(worker.contains("MigrationBatchDiscoveryMode.DIRECT_SEARCH->autoFix.searchReplacements"))
		assertTrue(worker.contains("directSearch=true"))
	}

	@Test
	fun `primary discovery query strips lightweight title decorators while aliases stay execution fallback`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val primary = autoFix.substringAfter("internalfunbuildPrimaryMigrationSearchQuery(").substringBefore("internalfunbuildMigrationSearchQueries(")
		val fallback = autoFix.substringAfter("internalfunbuildMigrationSearchQueries(")

		assertTrue(primary.contains("migrationTitleVariants(seed.title).lastOrNull()"))
		assertTrue(primary.contains("TRAILING_MIGRATION_DECORATOR"))
		assertTrue(fallback.contains("addQuery(buildPrimaryMigrationSearchQuery(seed))"))
		assertTrue(fallback.contains("seed.altTitles"))
	}

	@Test
	fun `optional current source check is bounded and probes the newest normalized chapter`() {
		val autoFix = source("org/koitharu/kotatsu/alternatives/domain/AutoFixUseCase.kt")
		val check = autoFix.substringAfter("suspendfuncheckCurrentSource(").substringBefore("suspendfunsearchAvailability(")
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
