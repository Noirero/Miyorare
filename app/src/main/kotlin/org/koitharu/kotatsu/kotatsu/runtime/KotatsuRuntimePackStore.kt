package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Owns verified current/previous runtime slots and their matching manifests. */
internal class KotatsuRuntimePackStore(private val context: Context) {
	private val root = File(context.noBackupFilesDir, "kotatsu-runtime")
	val currentFile: File get() = File(root, "current.apk")
	val previousFile: File get() = File(root, "previous.apk")
	private val currentManifestFile get() = File(root, "current.json")
	private val previousManifestFile get() = File(root, "previous.json")
	private val stagingFile get() = File(root, "staging.apk")
	private val stagingManifestFile get() = File(root, "staging.json")

	fun currentManifest(): KotatsuRuntimePackManifest? = readManifest(currentManifestFile)
	fun previousManifest(): KotatsuRuntimePackManifest? = readManifest(previousManifestFile)

	@Synchronized
	fun install(candidate: File, manifest: KotatsuRuntimePackManifest, verifier: KotatsuRuntimePackVerifier) {
		root.mkdirs()
		stagingFile.delete(); stagingManifestFile.delete()
		copyDurably(candidate, stagingFile)
		stagingManifestFile.writeText(manifest.toJson())
		require(stagingFile.setReadOnly()) { "Could not make staged Kotatsu runtime read-only" }
		require(stagingManifestFile.setReadOnly()) { "Could not make staged Kotatsu manifest read-only" }
		verifier.verify(stagingFile, manifest).getOrThrow()

		val backupApk = File(root, "previous.tmp.apk")
		val backupManifest = File(root, "previous.tmp.json")
		backupApk.delete(); backupManifest.delete()
		if (currentFile.exists()) require(currentFile.renameTo(backupApk)) { "Could not stage current Kotatsu runtime" }
		if (currentManifestFile.exists()) require(currentManifestFile.renameTo(backupManifest)) { "Could not stage current Kotatsu manifest" }
		try {
			require(stagingFile.renameTo(currentFile)) { "Could not activate Kotatsu runtime pack" }
			require(stagingManifestFile.renameTo(currentManifestFile)) { "Could not activate Kotatsu runtime manifest" }
		} catch (error: Throwable) {
			currentFile.delete(); currentManifestFile.delete()
			backupApk.renameTo(currentFile); backupManifest.renameTo(currentManifestFile)
			throw error
		}
		previousFile.delete(); previousManifestFile.delete()
		if (backupApk.exists()) backupApk.renameTo(previousFile)
		if (backupManifest.exists()) backupManifest.renameTo(previousManifestFile)
	}

	@Synchronized
	fun rollback(): Boolean {
		if (!previousFile.isFile || !previousManifestFile.isFile) return false
		val failedApk = File(root, "failed.apk")
		val failedManifest = File(root, "failed.json")
		failedApk.delete(); failedManifest.delete()
		if (currentFile.exists() && !currentFile.renameTo(failedApk)) return false
		if (currentManifestFile.exists() && !currentManifestFile.renameTo(failedManifest)) {
			failedApk.renameTo(currentFile); return false
		}
		if (!previousFile.renameTo(currentFile) || !previousManifestFile.renameTo(currentManifestFile)) {
			currentFile.delete(); currentManifestFile.delete()
			failedApk.renameTo(currentFile); failedManifest.renameTo(currentManifestFile)
			return false
		}
		failedApk.delete(); failedManifest.delete(); return true
	}

	private fun readManifest(file: File): KotatsuRuntimePackManifest? =
		file.takeIf(File::isFile)?.let { runCatching { KotatsuRuntimePackManifest.parse(it.readText()) }.getOrNull() }

	private fun copyDurably(source: File, target: File) {
		source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
	}
}
