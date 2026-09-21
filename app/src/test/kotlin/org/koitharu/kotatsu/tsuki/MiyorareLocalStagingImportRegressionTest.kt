package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiyorareLocalStagingImportRegressionTest {

	@Test
	fun `global staging import replaces official identity and is protected from auto updater`() {
		val installer = source("kotlin/org/koitharu/kotatsu/tsuki/TsukiPluginInstaller.kt")
			.replace(Regex("\\s+"), "")
		val worker = source("kotlin/org/koitharu/kotatsu/tsuki/MiyorareSourcePackUpdateWorker.kt")
			.replace(Regex("\\s+"), "")
		val session = source("kotlin/org/koitharu/kotatsu/tsuki/EhentaiSessionManager.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(installer.contains("localMiyorareGlobalStagingConfig(displayName)"))
		assertTrue(installer.contains("provider=TsukiPluginProvider.MIYORARE"))
		assertTrue(installer.contains("MiyorareOfficialSourcePacks.LOCAL_STAGING_ORIGIN_PREFIX"))
		assertTrue(installer.contains("MiyorareOfficialSourcePacks.LOCAL_STAGING_VERSION"))

		assertTrue(worker.contains("localStagingPackIds"))
		assertTrue(worker.contains("filterNot{itinlocalStagingPackIds}"))

		// Session credentials remain app-owned and independent from the source-pack JAR replacement.
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
