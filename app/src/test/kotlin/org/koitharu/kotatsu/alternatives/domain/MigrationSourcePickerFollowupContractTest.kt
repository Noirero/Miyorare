package org.koitharu.kotatsu.alternatives.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationSourcePickerFollowupContractTest {

	@Test
	fun `Yokai migration includes installed official Miyorare sources without widening third party Tsuki semantics`() {
		val source = source("org/koitharu/kotatsu/alternatives/domain/YokaiStyleMigrationSearchUseCase.kt")

		assertTrue(source.contains("alternativesUseCase.getMigrationSourcePool(seed,excludeCurrent=false)"))
		assertTrue(source.contains("tsukiPluginManager.getAvailableSources()"))
		assertTrue(source.contains("source.plugin.provider==TsukiPluginProvider.MIYORARE"))
		assertTrue(source.contains("MiyorareOfficialSourcePacks.findByInstalledPluginId(source.plugin.pluginId)!=null"))
		assertTrue(source.contains("source.isNovelContentSource==seed.isNovelContent"))
		assertTrue(source.contains("appSettings.isNsfwContentDisabled"))
		assertTrue(source.contains("base.allSources+officialMiyorareSources"))
		assertFalse(source.contains("filter{source->source.plugin.provider!=TsukiPluginProvider.MIYORARE"))
	}

	@Test
	fun `manual candidate selection returns to results without executing`() {
		val screen = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchFragment.kt")
		val vm = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchViewModel.kt")
		val scheduler = source("org/koitharu/kotatsu/alternatives/work/MigrationBatchScheduler.kt")

		assertTrue(screen.contains("onUseSelected=viewModel::useSelected"))
		assertTrue(screen.contains("Button(onClick=onUseSelected"))
		assertTrue(screen.contains("state.selectedReady"))
		assertFalse(screen.contains("if(pendingAction!=null&&selected!=null"))
		assertTrue(vm.contains("batchScheduler.markSelected("))
		assertTrue(scheduler.contains("MigrationBatchOutcome(MigrationBatchStatus.READY"))
	}

	@Test
	fun `source first entry supports single and whole source actions`() {
		val source = source("org/koitharu/kotatsu/settings/sources/migration/BrokenSourcesMigrationFragment.kt")
		assertTrue(source.contains("Text(\"Pilihsource\""))
		assertTrue(source.contains("onOpenManga(listOf(item.id))"))
		assertTrue(source.contains("onOpenManga(ids)"))
		assertFalse(source.contains("onToggleManga=viewModel::toggleManga"))
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
