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
	fun `manual result selection expands confirmation at the selected result row`() {
		val source = source("org/koitharu/kotatsu/settings/sources/migration/MigrationManualSearchFragment.kt")

		assertTrue(source.contains("selectionIsInResults"))
		assertTrue(source.contains("if(selected!=null&&!selectionIsInResults)"))
		assertTrue(source.contains("if(manga.sameMigrationIdentity(selected)){MigrationManualSelectionCard("))
		assertTrue(source.contains("clickable(enabled=!busy){onSelect(manga)}"))
		assertTrue(source.contains("privatefunManga.sameMigrationIdentity(other:Manga?)"))
		assertFalse(source.contains("state.selected?.let{selected->item{Card("))
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
