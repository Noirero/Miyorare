package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.alternatives.work.MigrationBatchWork

class MigrationYokaiFlowContractTest {

	@Test
	fun `migration manual and automatic search reuse global SIMPLE semantics`() {
		val bridge = source("org/koitharu/kotatsu/alternatives/domain/MigrationGlobalSearchBridge.kt")
		val automatic = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationSearchUseCase.kt")
		val manual = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchViewModel.kt")

		assertTrue(bridge.contains("SearchKind.SIMPLE"))
		assertFalse(bridge.contains("SearchKind.TITLE"))
		assertTrue(automatic.contains("globalSearch.search(source,query)"))
		assertTrue(automatic.contains("replace(\"’\",\"'\")"))
		assertTrue(manual.contains("globalSearch.search(source,query)"))
	}

	@Test
	fun `automatic matching follows Yokai source order and threshold rules`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationSearchUseCase.kt")

		assertTrue(source.contains("for((index,source)insources.withIndex())"))
		assertTrue(source.contains("if(selected.size==1)selectedelseselected.filter{it.name!=seed.source.name}"))
		assertTrue(source.contains("if(results.size==1)returnresults.single()"))
		assertTrue(source.contains("MIN_ELIGIBLE_SIMILARITY=0.4"))
		assertTrue(source.contains("Semaphore(MAX_PARALLEL_SOURCES)"))
		assertTrue(source.contains("MAX_PARALLEL_SOURCES=3"))
		assertTrue(source.contains("it.chapters.orEmpty().size"))
		assertTrue(source.contains("result.id==seed.id"))
		assertFalse(source.contains("checkCurrentSource("))
	}

	@Test
	fun `Yokai setup can list current source while legacy pool still defaults to excluding it`() {
		val automatic = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationSearchUseCase.kt")
		val alternatives = source("org/koitharu/kotatsu/alternatives/domain/AlternativesUseCase.kt")

		assertTrue(automatic.contains("getMigrationSourcePool(loadSeed(mangaId),excludeCurrent=false)"))
		assertTrue(alternatives.contains("excludeCurrent:Boolean=true"))
	}

	@Test
	fun `candidate persistence cannot overwrite source manga on numeric id collision`() {
		val automatic = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationSearchUseCase.kt")
		val manual = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchViewModel.kt")

		assertTrue(automatic.contains("filterNot{result->result.id==seed.id}"))
		assertTrue(manual.contains("if(candidate.id==seed.id)continue"))
		assertTrue(manual.contains("candidate.id==mangaId"))
	}

	@Test
	fun `manual preselection is discarded when source setup changed`() {
		val manual = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchViewModel.kt")

		assertTrue(manual.contains("takeIf{it.source.nameinselectedSourceNames}"))
	}

	@Test
	fun `execution refreshes exact persisted identity then uses Miyorare lossless transfer`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationExecutionUseCase.kt")

		assertTrue(source.contains("findMangaById(selection.id,withChapters=false)"))
		assertTrue(source.contains("storedCandidate.source.name==selection.source"))
		assertTrue(source.contains("repository.getDetails(storedCandidate)"))
		assertTrue(source.contains("repository.getPages(chapter)"))
		assertTrue(source.contains("lossless=true"))
		assertTrue(source.contains("requireLibraryEntry=true"))
		assertFalse(source.contains("search("))
	}

	@Test
	fun `single migration routes into the same source manga session as batch`() {
		val settings = source("org/koitharu/kotatsu/settings/SettingsActivity.kt")
		val action = settings.substringAfter("AppRouter.ACTION_MIGRATION_REVIEW->")
			.substringBefore("AppRouter.ACTION_MIGRATION->")

		assertTrue(action.contains("SourceMangaListFragment"))
		assertTrue(action.contains("argsForMangaIds("))
		assertFalse(action.contains("MigrationReviewFragment"))
	}

	@Test
	fun `explicit single migration session refreshes after ownership moves`() {
		val source = source("org/koitharu/kotatsu/settings/sources/migration/SourceMangaListViewModel.kt")

		assertTrue(source.contains("if(explicitMangaIds.isEmpty())return"))
		assertTrue(source.contains("loadJob?.cancel()"))
		assertTrue(source.contains("dao.isMigrationEntryVisible(it.manga.id)"))
	}

	@Test
	fun `session key changes when ordered sources strategy or extra search changes`() {
		val base = migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.FIRST_MATCH, null)
		assertNotEquals(base, migrationConfigKey(listOf("B", "A"), MigrationSourceStrategy.FIRST_MATCH, null))
		assertNotEquals(base, migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.MOST_CHAPTERS, null))
		assertNotEquals(base, migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.FIRST_MATCH, "uncensored"))
		assertEquals(base, migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.FIRST_MATCH, "  "))
	}

	@Test
	fun `work request round trip preserves Yokai migration config`() {
		val request = MigrationBatchRequest(
			mangaId = 42L,
			source = "OLD",
			action = MigrationBatchAction.CHECK,
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
			selectedSourceNames = listOf("B", "A"),
			sourceStrategy = MigrationSourceStrategy.MOST_CHAPTERS,
			extraSearchParams = "uncensored",
		)
		val restored = MigrationBatchWork.request(MigrationBatchWork.input(request))

		assertEquals(request.mangaId, restored.mangaId)
		assertEquals(request.discoveryMode, restored.discoveryMode)
		assertEquals(request.selectedSourceNames, restored.selectedSourceNames)
		assertEquals(request.sourceStrategy, restored.sourceStrategy)
		assertEquals(request.extraSearchParams, restored.extraSearchParams)
		assertEquals(request.configKey, restored.configKey)
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
