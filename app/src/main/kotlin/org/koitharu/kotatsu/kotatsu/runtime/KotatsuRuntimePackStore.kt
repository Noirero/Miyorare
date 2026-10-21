package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the on-device runtime slots. Installation is staged and verified before the active slot is
 * changed; the previous verified pack is retained for rollback.
 */
internal class KotatsuRuntimePackStore(private val context: Context) {

	private val root = File(context.noBackupFilesDir, "kotatsu-runtime")
	val currentFile: File get() = File(root, "current.apk")
	val previousFile: File get() = File(root, "previous.apk")
	private val stagingFile: File get() = File(root, "staging.apk")

	@Synchronized
	fun install(candidate: File, manifest: KotatsuRuntimePackManifest, verifier: KotatsuRuntimePackVerifier) {
		root.mkdirs()
		stagingFile.delete()
		copyDurably(candidate, stagingFile)
		verifier.verify(stagingFile, manifest).getOrThrow()

		val backup = File(root, "previous.tmp")
		backup.delete()
		if (currentFile.exists()) {
			require(currentFile.renameTo(backup)) { "Unable to stage current Kotatsu runtime for rollback" }
		}
		if (!stagingFile.renameTo(currentFile)) {
			backup.renameTo(currentFile)
			throw IllegalStateException("Unable to activate Kotatsu runtime pack")
		}
		previousFile.delete()
		if (backup.exists() && !backup.renameTo(previousFile)) {
			// Active pack is already committed; losing the optional rollback slot must not corrupt it.
			backup.delete()
		}
	}

	@Synchronized
	fun rollback(): Boolean {
		if (!previousFile.isFile) return false
		val failed = File(root, "failed.apk")
		failed.delete()
		if (currentFile.exists() && !currentFile.renameTo(failed)) return false
		if (!previousFile.renameTo(currentFile)) {
			failed.renameTo(currentFile)
			return false
		}
		failed.delete()
		return true
	}

	private fun copyDurably(source: File, target: File) {
		source.inputStream().use { input ->
			FileOutputStream(target).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}
	}
}
