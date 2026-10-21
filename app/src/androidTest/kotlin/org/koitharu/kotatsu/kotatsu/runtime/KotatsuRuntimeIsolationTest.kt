package org.koitharu.kotatsu.kotatsu.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
			"The target APK parser ABI must not contain the runtime-only fixture marker",
			hostParser.methods.any { it.name == "runtimeFixtureMarker" },
		)

		val runtimeApk = runtimeFixture()
		assertNotEquals(
			"The runtime fixture must not be the instrumentation APK",
			File(instrumentation.context.applicationInfo.sourceDir).canonicalPath,
			runtimeApk.canonicalPath,
		)
		assertNotEquals(
			"The runtime fixture must not be the target APK",
			File(targetContext.applicationInfo.sourceDir).canonicalPath,
			runtimeApk.canonicalPath,
		)

		val manager = KotatsuRuntimeManager(targetContext)
		manager.install(runtimeApk, fixtureManifest(runtimeApk))

		val source = manager.getAvailableSources().single()
		assertEquals("KOTATSU:FIXTURE", source.name)
		assertEquals("Runtime Fixture", source.displayName)
		assertTrue(manager.resolveSource("KOTATSU:FIXTURE") === source)

		manager.setSourceEnabled(source.sourceKey, true)
		assertEquals(listOf(source), manager.getEnabledSources())
	}

	@Test
	fun catalogMismatchIsRejectedBeforeReplacingCurrentRuntime() {
		val runtimeApk = runtimeFixture()
		val manager = KotatsuRuntimeManager(targetContext)
		manager.install(runtimeApk, fixtureManifest(runtimeApk))
		assertEquals(KotatsuRuntimeManager.RuntimeOrigin.CURRENT, manager.status().origin)

		val failure = runCatching {
			manager.install(runtimeApk, fixtureManifest(runtimeApk, sourceCount = 2))
		}.exceptionOrNull()

		assertNotNull("Catalog mismatch must reject the staged candidate", failure)
		assertEquals(KotatsuRuntimeManager.RuntimeOrigin.CURRENT, manager.status().origin)
		assertEquals("KOTATSU:FIXTURE", manager.getAvailableSources().single().name)
		assertTrue(File(runtimeRoot, "current/runtime.apk").isFile)
		assertFalse("Rejected staging slot must be removed", File(runtimeRoot, "staging.tmp").exists())
	}

	@Test
	fun initializeRecoversPreviousWhenActivationWasInterruptedAfterCurrentRename() {
		val runtimeApk = runtimeFixture()
		KotatsuRuntimeManager(targetContext).install(runtimeApk, fixtureManifest(runtimeApk))

		val current = File(runtimeRoot, "current")
		val previous = File(runtimeRoot, "previous")
		assertTrue("Fixture install must create current runtime slot", current.isDirectory)
		assertTrue("Test must simulate current-to-previous activation step", current.renameTo(previous))
		assertFalse(current.exists())
		assertTrue(File(previous, "runtime.apk").isFile)

		val recovered = KotatsuRuntimeManager(targetContext)
		recovered.initialize()

		assertEquals(KotatsuRuntimeManager.RuntimeOrigin.ROLLED_BACK, recovered.status().origin)
		assertEquals("KOTATSU:FIXTURE", recovered.getAvailableSources().single().name)
		assertTrue("Recovered previous slot must become current again", File(runtimeRoot, "current/runtime.apk").isFile)
		assertFalse("Previous slot is consumed by rollback", previous.exists())
	}

	private fun runtimeFixture(): File {
		val runtimeApkPath = InstrumentationRegistry.getArguments().getString(ARG_RUNTIME_FIXTURE_APK)
			requireNotNull(runtimeApkPath) { "Missing instrumentation argument: $ARG_RUNTIME_FIXTURE_APK" }
		return File(runtimeApkPath).also {
			assertTrue("The separately installed runtime fixture APK must be readable", it.isFile)
		}
	}

	private fun fixtureManifest(file: File, sourceCount: Int = 1) = KotatsuRuntimePackManifest(
		protocolVersion = KotatsuRuntimePackContract.PROTOCOL_VERSION,
		packVersion = "android-runtime-fixture",
		upstreamRevision = "dc223c3d8098415b29cf1acfedc401a88d01c9fe",
		sourceCount = sourceCount,
		sha256 = sha256(file),
		downloadUrl = "https://example.invalid/kotatsu-runtime-fixture.apk",
	)

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count <= 0) break
				digest.update(buffer, 0, count)
			}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		private const val ARG_RUNTIME_FIXTURE_APK = "runtimeFixtureApk"
	}
}
