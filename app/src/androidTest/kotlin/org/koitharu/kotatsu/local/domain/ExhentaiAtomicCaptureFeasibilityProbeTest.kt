package org.koitharu.kotatsu.local.domain

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Retired atomic capture feasibility probes, not product acceptance tests.
 * Original Android 15 evidence: 7eec405, workflow 45344840215.
 * Run explicitly only for a new feasibility hypothesis. The PR runtime class selector
 * intentionally does not select this class. All original probe bodies are preserved.
 */
@RunWith(AndroidJUnit4::class)
class ExhentaiAtomicCaptureFeasibilityProbeTest {
	private lateinit var root: File

	@Before fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		root = File(context.cacheDir, "exhentai-title-${System.nanoTime()}").canonicalFile
		check(root.mkdirs())
		check(File(root, "private").mkdirs())
	}

	@After fun tearDown() {
		if (::root.isInitialized) root.deleteRecursively()
	}

	// Capability probes only. Never invoke the production delete path or touch user downloads.
	// A passing probe characterizes the fixture backend, not a universal Safe Delete guarantee.
	private fun withPrivateCaptureFixture(block: (File, File) -> Unit) {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val staging = File(context.noBackupFilesDir, "exhentai-capture-probe-" + UUID.randomUUID())
		Os.mkdir(staging.path, 0x1c0) // 0700; newly allocated, app-internal directory.
		try {
			val stat = Os.lstat(staging.path)
			assertTrue(OsConstants.S_ISDIR(stat.st_mode))
			assertEquals(Os.getuid(), stat.st_uid)
			assertEquals(0x1c0, stat.st_mode and 0x1ff)
			block(File(root, "capture-probe.cbz"), File(staging, "captured.cbz"))
		} finally {
			check(staging.deleteRecursively()) // Only the unique synthetic fixture.
		}
	}

	private fun captureProbeReport(message: String) {
		android.util.Log.i("ExhentaiCaptureProbe", message)
		println("ExhentaiCaptureProbe: $message")
	}

	@Test fun atomicCaptureProbeVerifiesCapturedInodeAndRestoresMismatchWithoutOverwrite() {
		withPrivateCaptureFixture { source, staged ->
			val approvedBytes = byteArrayOf(11, 22, 33)
			val replacementBytes = byteArrayOf(44, 55, 66)
			source.writeBytes(approvedBytes)
			val approved = Os.lstat(source.path)
			val original = File(root, "capture-original.cbz")
			Os.rename(source.path, original.path)
			source.writeBytes(replacementBytes)
			val replacement = Os.lstat(source.path)
			assertTrue(approved.st_ino != replacement.st_ino || approved.st_dev != replacement.st_dev)
			assertEquals(Os.lstat(staged.parentFile!!.path).st_dev, replacement.st_dev)
			// Rename captures the entry at the operation, not the earlier approved identity.
			Os.rename(source.path, staged.path)
			assertFalse(source.exists())
			val captured = Os.lstat(staged.path)
			assertEquals(replacement.st_ino, captured.st_ino)
			assertEquals(replacement.st_dev, captured.st_dev)
			assertArrayEquals(replacementBytes, staged.readBytes())
			assertArrayEquals(approvedBytes, original.readBytes())
			assertTrue(approved.st_ino != captured.st_ino || approved.st_dev != captured.st_dev)
			// link(2) refuses an existing destination; unlike rename it cannot overwrite it.
			Os.link(staged.path, source.path)
			assertEquals(captured.st_ino, Os.lstat(source.path).st_ino)
			Os.remove(staged.path)
			assertArrayEquals(replacementBytes, source.readBytes())
			assertArrayEquals(approvedBytes, original.readBytes())
			captureProbeReport("internal: CAPTURE_MISMATCH_RESTORED")
		}
	}

	@Test fun atomicCaptureProbeCollisionRetainsBothFilesAcrossRecoveryRetry() {
		withPrivateCaptureFixture { source, staged ->
			val capturedBytes = byteArrayOf(7, 8, 9)
			val occupantBytes = byteArrayOf(1, 2, 3)
			source.writeBytes(capturedBytes)
			Os.rename(source.path, staged.path)
			val captured = Os.lstat(staged.path)
			// Simulate an interrupted recovery and a new occupant of the original pathname.
			// Reopen the staged pathname rather than trusting an in-memory file handle.
			source.writeBytes(occupantBytes)
			val reopened = File(staged.path)
			val collision = runCatching { Os.link(reopened.path, source.path) }.exceptionOrNull()
			assertTrue(collision is ErrnoException)
			assertEquals(OsConstants.EEXIST, (collision as ErrnoException).errno)
			assertArrayEquals(occupantBytes, source.readBytes())
			assertArrayEquals(capturedBytes, reopened.readBytes())
			assertEquals(captured.st_ino, Os.lstat(reopened.path).st_ino)
			// No automatic overwrite/removal on collision. A later unoccupied path can recover.
			// Remove only the synthetic occupant to exercise a second recovery attempt.
			Os.remove(source.path)
			Os.link(reopened.path, source.path)
			Os.remove(reopened.path)
			assertArrayEquals(capturedBytes, source.readBytes())
			captureProbeReport("internal: COLLISION_RETAINED_BOTH; RETRY_RESTORED")
		}
	}

	@Test fun atomicCaptureProbeDoesNotFreezePreviouslyOpenedWriter() {
		withPrivateCaptureFixture { source, staged ->
			val approvedBytes = byteArrayOf(12, 34, 56)
			val modifiedBytes = byteArrayOf(65, 43, 21)
			source.writeBytes(approvedBytes)
			java.io.RandomAccessFile(source, "rw").use { writer ->
				Os.rename(source.path, staged.path)
				assertArrayEquals(approvedBytes, staged.readBytes())
				val before = Os.lstat(staged.path)
				// Negative capability: private pathname does not revoke a previously open FD.
				writer.seek(0L)
				writer.write(modifiedBytes)
				writer.fd.sync()
				assertEquals(before.st_ino, Os.lstat(staged.path).st_ino)
				assertArrayEquals(modifiedBytes, staged.readBytes())
			}
			Os.link(staged.path, source.path)
			Os.remove(staged.path)
			assertArrayEquals(modifiedBytes, source.readBytes())
			captureProbeReport("internal: OPEN_WRITER_CAN_MUTATE_CAPTURED_INODE")
		}
	}

	@Test fun atomicCaptureProbeExternalBackendsFailClosedWithoutCopyFallback() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val volumes = context.getExternalFilesDirs(null).filterNotNull()
		if (volumes.isEmpty()) captureProbeReport("external: NO_VOLUME_AVAILABLE")
		for ((index, volume) in volumes.withIndex()) {
			val label = "external[$index] removable=" + android.os.Environment.isExternalStorageRemovable(volume)
			val fixture = File(volume, "exhentai-capture-probe-" + UUID.randomUUID())
			if (!fixture.mkdir()) {
				captureProbeReport("$label: UNAVAILABLE")
				continue
			}
			try {
				withPrivateCaptureFixture { _, staged ->
					val source = File(fixture, "source.cbz")
					val bytes = byteArrayOf(3, 1, 4, 1, 5)
					source.writeBytes(bytes)
					val sourceStat = Os.lstat(source.path)
					val stageStat = Os.lstat(staged.parentFile!!.path)
					val renameError = runCatching { Os.rename(source.path, staged.path) }.exceptionOrNull()
					if (renameError != null) {
						assertTrue(renameError is ErrnoException)
						assertArrayEquals(bytes, source.readBytes())
						assertFalse(staged.exists())
						captureProbeReport("$label: PRIVATE_CAPTURE_REJECTED errno=" +
							(renameError as ErrnoException).errno + " sameDev=" + (sourceStat.st_dev == stageStat.st_dev))
					} else {
						assertFalse(source.exists())
						assertArrayEquals(bytes, staged.readBytes())
						val captured = Os.lstat(staged.path)
						assertEquals(sourceStat.st_ino, captured.st_ino)
						assertEquals(sourceStat.st_dev, captured.st_dev)
						val restoreError = runCatching { Os.link(staged.path, source.path) }.exceptionOrNull()
						if (restoreError != null) {
							assertTrue(restoreError is ErrnoException)
							assertArrayEquals(bytes, staged.readBytes())
							assertFalse(source.exists())
							captureProbeReport("$label: PRIVATE_CAPTURE_OK_RECOVERY_UNSUPPORTED errno=" +
								(restoreError as ErrnoException).errno)
						} else {
							Os.remove(staged.path)
							assertArrayEquals(bytes, source.readBytes())
							captureProbeReport("$label: PRIVATE_CAPTURE_AND_LINK_RESTORE_OK")
						}
					}
					// Probe same-volume staging separately: mode bits alone do not establish
					// protection from other UIDs on emulated/removable storage.
					val sameVolume = File(fixture, "stage")
					val mkdirError = runCatching { Os.mkdir(sameVolume.path, 0x1c0) }.exceptionOrNull()
					if (mkdirError != null) {
						assertTrue(mkdirError is ErrnoException)
						captureProbeReport("$label: SAME_VOLUME_STAGE_UNSUPPORTED errno=" +
							(mkdirError as ErrnoException).errno)
					} else {
						val mode = Os.lstat(sameVolume.path).st_mode and 0x1ff
						val localSource = File(fixture, "same-volume.cbz").apply { writeBytes(bytes) }
						val localStaged = File(sameVolume, "captured.cbz")
						val error = runCatching { Os.rename(localSource.path, localStaged.path) }.exceptionOrNull()
						if (error != null) {
							assertTrue(error is ErrnoException)
							assertArrayEquals(bytes, localSource.readBytes())
							assertFalse(localStaged.exists())
							captureProbeReport("$label: SAME_VOLUME_RENAME_REJECTED errno=" +
								(error as ErrnoException).errno + " mode=" + mode)
						} else {
							assertArrayEquals(bytes, localStaged.readBytes())
							assertFalse(localSource.exists())
							val restore = runCatching { Os.link(localStaged.path, localSource.path) }.exceptionOrNull()
							if (restore != null) {
								assertTrue(restore is ErrnoException)
								assertArrayEquals(bytes, localStaged.readBytes())
								assertFalse(localSource.exists())
								captureProbeReport("$label: SAME_VOLUME_LINK_UNSUPPORTED errno=" +
									(restore as ErrnoException).errno + " mode=" + mode)
							} else {
								assertArrayEquals(bytes, localSource.readBytes())
								val collision = runCatching { Os.link(localStaged.path, localSource.path) }.exceptionOrNull()
								assertTrue(collision is ErrnoException)
								assertEquals(OsConstants.EEXIST, (collision as ErrnoException).errno)
								Os.remove(localStaged.path)
								assertArrayEquals(bytes, localSource.readBytes())
								captureProbeReport("$label: SAME_VOLUME_RENAME_LINK_OK mode=" + mode)
							}
						}
					}
				}
			} finally {
				check(fixture.deleteRecursively()) // Only this probe's synthetic files.
			}
		}
	}

}
