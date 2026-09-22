package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiyorareLocalImportRegressionTest {

	@Test
	fun `local imports stay generic and former staging packs can return to official updates`() {
		val installer = source("kotlin/org/koitharu/kotatsu/tsuki/TsukiPluginInstaller.kt")
			.replace(Regex("\\s+"), "")
		val worker = source("kotlin/org/koitharu/kotatsu/tsuki/MiyorareSourcePackUpdateWorker.kt")
			.replace(Regex("\\s+"), "")
		val official = source("kotlin/org/koitharu/kotatsu/tsuki/MiyorareOfficialSourcePacks.kt")
			.replace(Regex("\\s+"), "")
		val session = source("kotlin/org/koitharu/kotatsu/tsuki/EhentaiSessionManager.kt")
			.replace(Regex("\\s+"), "")

		assertFalse(installer.contains("localMiyorareGlobalStagingConfig"))
		assertFalse(installer.contains("LOCAL_STAGING_ORIGIN_PREFIX"))
		assertFalse(installer.contains("LOCAL_STAGING_VERSION"))
		assertFalse(worker.contains("localStagingPackIds"))
		assertFalse(worker.contains("LOCAL_STAGING_ORIGIN_PREFIX"))
		assertFalse(official.contains("local://staging/"))
		assertFalse(official.contains("local-staging"))

		assertTrue(installer.contains("valconfig=inferLocalProvider(displayName)"))
		assertTrue(installer.contains("origin=\"local://import/\${Uri.encode(displayName)}\""))
		assertTrue(installer.contains("version=null"))

		// Existing descriptors that were previously persisted as MIYORARE are intentionally no longer
		// excluded from this list, so the official updater can replace them with the sealed release.
		assertTrue(worker.contains("filter{it.provider==TsukiPluginProvider.MIYORARE}"))
		assertTrue(worker.contains("mapNotNull{MiyorareOfficialSourcePacks.findByInstalledPluginId(it.pluginId)?.pluginId}"))

		// ExHentai credentials remain app-owned; retiring staging must not affect the working session.
		assertTrue(session.contains("valSECRET_FILE=\"miyorare-ehentai-session-v1.json\""))
		assertTrue(session.contains("syncRuntimeCookies(getMode(),getCredentials())"))
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
}
