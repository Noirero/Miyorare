package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionInstallerMethodDialogRegressionTest {

	@Test
	fun `installer method picker uses separate cards and staged confirmation`() {
		val picker = source("kotlin/org/koitharu/kotatsu/settings/sources/ExtensionInstallerMethodDialog.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(picker.contains("InstallerMethodCard("))
		assertTrue(picker.contains("RadioButton("))
		assertTrue(picker.contains("extension_installer_choose_message"))
		assertTrue(picker.contains("extension_installer_shizuku_tag"))
		assertTrue(picker.contains("extension_installer_system_tag"))
		assertTrue(picker.contains("extension_installer_private_tag"))
		assertTrue(picker.contains("ExpressivePillButton("))
		assertTrue(picker.contains("extension_installer_confirm"))
		assertTrue(picker.contains("onConfirm(selected)"))
		assertTrue(picker.contains("showComposeDialog("))
	}

	@Test
	fun `settings and catalog share the same installer picker`() {
		val settings = source("kotlin/org/koitharu/kotatsu/settings/sources/ExtensionsSettingsFragment.kt")
			.replace(Regex("\\s+"), "")
		val catalog = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourcesCatalogActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("showExtensionInstallerMethodPicker("))
		assertTrue(catalog.contains("showExtensionInstallerMethodPicker("))
		assertFalse(settings.contains("setItems(labels)"))
		assertFalse(catalog.contains("setItems(labels)"))
	}

	@Test
	fun `Indonesian installer copy stays concise and scan friendly`() {
		val strings = sourceResource("values-in/strings_extension_installer.xml")
		assertTrue(strings.contains("Pilih metode instalasi ekstensi"))
		assertTrue(strings.contains("Tentukan cara memasang ekstensi yang paling cocok untukmu."))
		assertTrue(strings.contains("Paling otomatis"))
		assertTrue(strings.contains("Cara standar Android"))
		assertTrue(strings.contains("Hanya di dalam Miyorare"))
		assertTrue(strings.contains("Unduh → Pasang otomatis → Selesai"))
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

	private fun sourceResource(relativePath: String): String {
		return sequenceOf(
			File("src/main/res", relativePath),
			File("app/src/main/res", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find resource: $relativePath")
	}
}
