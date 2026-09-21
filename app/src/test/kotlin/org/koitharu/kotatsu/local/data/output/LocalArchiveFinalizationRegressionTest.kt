package org.koitharu.kotatsu.local.data.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LocalArchiveFinalizationRegressionTest {

	@Test
	fun `backup rename failure leaves the only archive untouched and readable`() {
		withArchives { root, temp, _ ->
			val ops = FaultOps(root, temp, failBackupRename = true)
			val failure = runCatching { commitRootReplacement(root, temp, ops) }.exceptionOrNull()

			assertTrue(failure is IllegalStateException)
			assertEquals(oldEntries, readZip(root))
			assertTrue(temp.isFile)
			assertFalse(File(root.path + ".bak" + LocalMangaOutput.SUFFIX_TMP).exists())
		}
	}

	@Test
	fun `disk full during fallback copy restores the previous archive`() {
		withArchives { root, temp, backup ->
			val ops = FaultOps(
				root = root,
				temp = temp,
				forceReplacementRenameFailure = true,
				failReplacementCopy = true,
			)
			val failure = runCatching { commitRootReplacement(root, temp, ops) }.exceptionOrNull()

			assertTrue(failure is IOException)
			assertEquals(oldEntries, readZip(root))
			assertFalse(backup.exists())
			assertEquals(0, ops.backupDeleteCalls)
		}
	}

	@Test
	fun `retry after process death never deletes the only backup before a failed commit`() {
		withArchives { root, temp, backup ->
			assertTrue(root.renameTo(backup))
			assertFalse(root.exists())
			assertEquals(oldEntries, readZip(backup))

			val ops = FaultOps(
				root = root,
				temp = temp,
				forceReplacementRenameFailure = true,
				failReplacementCopy = true,
			)
			val failure = runCatching { commitRootReplacement(root, temp, ops) }.exceptionOrNull()

			assertTrue(failure is IOException)
			assertEquals(0, ops.backupDeleteCalls)
			assertEquals(oldEntries, readZip(root))
			assertFalse(backup.exists())
		}
	}

	@Test
	fun `retry after process death can commit through copy fallback and keeps every new entry`() {
		withArchives { root, temp, backup ->
			assertTrue(root.renameTo(backup))
			val ops = FaultOps(
				root = root,
				temp = temp,
				forceReplacementRenameFailure = true,
			)

			commitRootReplacement(root, temp, ops)

			assertEquals(newEntries, readZip(root))
			assertFalse(temp.exists())
			assertFalse(backup.exists())
		}
	}


	@Test
	fun `stale backup from prior successful commit is never used to roll back a newer root`() {
		withArchives { root, temp, backup ->
			// Simulate a prior successful commit whose backup cleanup failed: both root and stale
			// backup exist. The current root is newer and must become the rollback source.
			writeZip(backup, oldEntries)
			writeZip(root, newEntries)
			writeZip(temp, retryEntries)

			val ops = FaultOps(
				root = root,
				temp = temp,
				forceReplacementRenameFailure = true,
				failReplacementCopy = true,
			)
			val failure = runCatching { commitRootReplacement(root, temp, ops) }.exceptionOrNull()

			assertTrue(failure is IOException)
			assertEquals(newEntries, readZip(root))
			assertFalse(backup.exists())
			assertTrue("stale backup should be cleared before current root is backed up", ops.backupDeleteCalls >= 1)
		}
	}

	@Test
	fun `failed restart recovery copy removes partial root and preserves the only backup for retry`() {
		withArchives { root, _, backup ->
			assertTrue(root.renameTo(backup))
			val ops = FaultOps(
				root = root,
				temp = File(root.path + LocalMangaOutput.SUFFIX_TMP),
				forceRecoveryRenameFailure = true,
				failRecoveryCopy = true,
			)

			val failure = runCatching { recoverInterruptedRootReplacement(root, ops) }.exceptionOrNull()

			assertTrue(failure is IOException)
			assertFalse("partial recovery must not mask the durable backup", root.exists())
			assertEquals(oldEntries, readZip(backup))

			assertTrue(recoverInterruptedRootReplacement(root))
			assertEquals(oldEntries, readZip(root))
			assertFalse(backup.exists())
		}
	}

	@Test
	fun `normal Local lookup recovery restores an orphaned backup after restart`() {
		withArchives { root, _, backup ->
			assertTrue(root.renameTo(backup))
			assertFalse(root.exists())

			assertTrue(recoverInterruptedRootReplacement(root))

			assertEquals(oldEntries, readZip(root))
			assertFalse(backup.exists())
		}
	}

	@Test
	fun `single cbz filtering uses the shared safe finalizer`() {
		val zip = source("org/koitharu/kotatsu/local/data/output/LocalMangaZipOutput.kt")
		val filter = zip
			.substringAfter("suspendfunfilterChapters(")
			.substringBefore("companionobject{", missingDelimiterValue = zip)

		assertTrue(filter.contains("subject.replaceFilteredRootFile()"))
		assertFalse(filter.contains("subject.rootFile.delete()"))
		assertFalse(filter.contains("subject.output.file.renameTo(subject.rootFile)"))
	}

	@Test
	fun `Local lookup repairs interrupted root replacement before probing the cbz`() {
		val output = source("org/koitharu/kotatsu/local/data/output/LocalMangaOutput.kt")
		val getImpl = output
			.substringAfter("privatesuspendfungetImpl(")
			.substringBefore("privatefunString.toReadableFileName")

		assertTrue(getImpl.contains("recoverInterruptedRootReplacement(zip)"))
		assertTrue(output.contains("varhasBackup=ops.exists(backup)"))
		assertTrue(output.contains("if(hadRoot){"))
		assertTrue(output.contains("Cannotclearstalebackup"))
		assertFalse(output.contains("backup.delete()valhadRoot"))
	}

	private inline fun withArchives(block: (root: File, temp: File, backup: File) -> Unit) {
		val dir = Files.createTempDirectory("miyorare-archive-finalizer").toFile()
		try {
			val root = File(dir, "title.cbz")
			val temp = File(root.path + LocalMangaOutput.SUFFIX_TMP)
			val backup = File(root.path + ".bak" + LocalMangaOutput.SUFFIX_TMP)
			writeZip(root, oldEntries)
			writeZip(temp, newEntries)
			block(root, temp, backup)
		} finally {
			dir.deleteRecursively()
		}
	}

	private fun writeZip(file: File, entries: Map<String, String>) {
		ZipOutputStream(file.outputStream().buffered()).use { zip ->
			entries.forEach { (name, value) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(value.toByteArray())
				zip.closeEntry()
			}
		}
	}

	private fun readZip(file: File): Map<String, String> {
		return ZipFile(file).use { zip ->
			zip.entries().asSequence().associate { entry ->
				entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
			}
		}
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}

	private class FaultOps(
		private val root: File,
		private val temp: File,
		private val failBackupRename: Boolean = false,
		private val forceReplacementRenameFailure: Boolean = false,
		private val failReplacementCopy: Boolean = false,
		private val forceRecoveryRenameFailure: Boolean = false,
		private val failRecoveryCopy: Boolean = false,
	) : RootFileCommitOps {

		private val backup = File(root.path + ".bak" + LocalMangaOutput.SUFFIX_TMP)
		var backupDeleteCalls: Int = 0
			private set

		override fun exists(file: File): Boolean = file.exists()

		override fun isFile(file: File): Boolean = file.isFile

		override fun length(file: File): Long = file.length()

		override fun delete(file: File): Boolean {
			if (file == backup) backupDeleteCalls++
			return file.delete()
		}

		override fun rename(source: File, target: File): Boolean {
			if (source == root && target == backup && failBackupRename) return false
			if (source == temp && target == root && forceReplacementRenameFailure) return false
			if (source == backup && target == root && forceRecoveryRenameFailure) return false
			return source.renameTo(target)
		}

		override fun copy(source: File, target: File) {
			if (source == temp && target == root && failReplacementCopy) {
				target.outputStream().use { it.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
				throw IOException("No space left on device")
			}
			if (source == backup && target == root && failRecoveryCopy) {
				target.outputStream().use { it.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
				throw IOException("Recovery copy failed")
			}
			source.copyTo(target, overwrite = true)
		}
	}

	private companion object {
		val oldEntries = linkedMapOf(
			"index.json" to "{\"version\":1}",
			"00000000_00010001.webp" to "old-page-1",
			"00000000_00010002.webp" to "old-page-2",
		)
		val newEntries = linkedMapOf(
			"index.json" to "{\"version\":2}",
			"00000000_00020001.webp" to "new-page-1",
			"00000000_00020002.webp" to "new-page-2",
			"00000000_00020003.webp" to "new-page-3",
		)
		val retryEntries = linkedMapOf(
			"index.json" to "{\"version\":3}",
			"00000000_00030001.webp" to "retry-page-1",
		)
	}
}
