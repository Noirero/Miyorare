package org.koitharu.kotatsu.kotatsu.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class KotatsuRuntimeIsolationTest {

	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val targetContext get() = instrumentation.targetContext
	private val runtimeRoot get() = File(targetContext.noBackupFilesDir, "kotatsu-runtime")

	@Before
	fun cleanRuntimeState() {
		runtimeRoot.deleteRecursively()
		targetContext.getSharedPreferences("kotatsu_runtime_sources", android.content.Context.MODE_PRIVATE)
			.edit().clear().commit()
	}

	@After
	fun cleanupRuntimeState() {
		runtimeRoot.deleteRecursively()
		targetContext.getSharedPreferences("kotatsu_runtime_sources", android.content.Context.MODE_PRIVATE)
			.edit().clear().commit()
	}

	@Test
	fun signedRuntimeApkCanShadowHostParserAbiWithoutLeakingRuntimeTypes() {
		val hostParser = targetContext.classLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
		assertFalse(
			"The host parser ABI must not contain the runtime-only fixture marker",
			hostParser.methods.any { it.name == "runtimeFixtureMarker" },
		)

		// The instrumentation APK is a real, independently dexed APK signed by the same debug identity
		// as the target APK. It deliberately carries the same parser FQCN with a different definition.
		val runtimeApk = File(instrumentation.context.applicationInfo.sourceDir)
		assertTrue(runtimeApk.isFile)
		val manager = KotatsuRuntimeManager(targetContext)
		manager.install(
			runtimeApk,
			KotatsuRuntimePackManifest(
				protocolVersion = KotatsuRuntimePackContract.PROTOCOL_VERSION,
				packVersion = "android-runtime-fixture",
				upstreamRevision = "dc223c3d8098415b29cf1acfedc401a88d01c9fe",
				sourceCount = 1,
				sha256 = sha256(runtimeApk),
				downloadUrl = "https://example.invalid/kotatsu-runtime-fixture.apk",
			),
		)

		val source = manager.getAvailableSources().single()
		assertEquals("KOTATSU:FIXTURE", source.name)
		assertEquals("Runtime Fixture", source.displayName)
		assertTrue(manager.resolveSource("KOTATSU:FIXTURE") === source)

		manager.setSourceEnabled(source.sourceKey, true)
		assertEquals(listOf(source), manager.getEnabledSources())
	}

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count <= 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}
