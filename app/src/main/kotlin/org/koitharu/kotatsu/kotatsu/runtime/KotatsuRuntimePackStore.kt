package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Owns verified bundled/current/previous runtime slots and their matching manifests. */
internal class KotatsuRuntimePackStore(private val context: Context) {
	private val root = File(context.noBackupFilesDir, "kotatsu-runtime")
	private val bundledDir get() = File(root, "bundled")
	private val currentDir get() = File(root, "current")
	private val previousDir get() = File(root, "previous")
	private val stagingDir get() = File(root, "staging.tmp")
	private val failedDir get() = File(root, "failed.tmp")

	val bundledFile: File get() = runtimeFile(bundledDir)
	val currentFile: File get() = runtimeFile(currentDir)
	val previousFile: File get() = runtimeFile(previousDir)
	private val bundledManifestFile get() = manifestFile(bundledDir)
	private val currentManifestFile get() = manifestFile(currentDir)
	private val previousManifestFile get() = manifestFile(previousDir)

	fun bundledManifest(): KotatsuRuntimePackManifest? = readManifest(bundledManifestFile)
	fun currentManifest(): KotatsuRuntimePackManifest? = readManifest(currentManifestFile)
	fun previousManifest(): KotatsuRuntimePackManifest? = readManifest(previousManifestFile)

	/**
	 * Materializes the immutable runtime embedded in the host APK. Runtime + manifest live in one
	 * directory so activation is a single same-filesystem directory rename rather than two unrelated
	 * file renames that can leave a torn pair after process/device interruption.
	 */
	@Synchronized
	fun ensureBundled(verifier: KotatsuRuntimePackVerifier): RuntimeSlot? = runCatching {
		root.mkdirs()
		val manifest = context.assets.open(BUNDLED_MANIFEST_ASSET).bufferedReader().use { reader ->
			KotatsuRuntimePackManifest.parse(reader.readText())
		}
		val existing = bundledManifest()
		if (existing?.sha256 == manifest.sha256 && verifier.verify(bundledFile, manifest).isSuccess) {
			return@runCatching RuntimeSlot(bundledFile, manifest)
		}

		val incoming = File(root, "bundled.tmp")
		incoming.deleteRecursively()
		writeSlot(
			directory = incoming,
			manifest = manifest,
			verifier = verifier,
		) { target -> context.assets.open(BUNDLED_APK_ASSET).use { input -> copyDurably(input, target) } }

		val old = File(root, "bundled.old")
		old.deleteRecursively()
		if (bundledDir.exists()) require(bundledDir.renameTo(old)) { "Could not stage previous bundled Kotatsu runtime" }
		try {
			require(incoming.renameTo(bundledDir)) { "Could not activate bundled Kotatsu runtime" }
		} catch (error: Throwable) {
			bundledDir.deleteRecursively()
			old.renameTo(bundledDir)
			throw error
		}
		old.deleteRecursively()
		RuntimeSlot(bundledFile, manifest)
	}.getOrNull()

	/** Copy and cryptographically verify a candidate without making it the active runtime. */
	@Synchronized
	fun stage(candidate: File, manifest: KotatsuRuntimePackManifest, verifier: KotatsuRuntimePackVerifier): RuntimeSlot {
		root.mkdirs()
		stagingDir.deleteRecursively()
		writeSlot(stagingDir, manifest, verifier) { target -> copyDurably(candidate, target) }
		return RuntimeSlot(runtimeFile(stagingDir), manifest)
	}

	/**
	 * Atomically promote an already verified and health-checked staged slot. The outgoing current
	 * slot becomes previous, so previous always represents a runtime that was active and healthy.
	 */
	@Synchronized
	fun activateStaged() {
		require(runtimeFile(stagingDir).isFile && manifestFile(stagingDir).isFile) {
			"No staged Kotatsu runtime is ready for activation"
		}
		val hadCurrent = currentDir.isDirectory
		if (hadCurrent) {
			previousDir.deleteRecursively()
			require(currentDir.renameTo(previousDir)) { "Could not stage current Kotatsu runtime as previous" }
		}
		try {
			require(stagingDir.renameTo(currentDir)) { "Could not atomically activate Kotatsu runtime pack" }
		} catch (error: Throwable) {
			currentDir.deleteRecursively()
			if (hadCurrent) previousDir.renameTo(currentDir)
			throw error
		}
	}

	@Synchronized
	fun discardStaged() {
		stagingDir.deleteRecursively()
	}

	@Synchronized
	fun discardCurrent() {
		currentDir.deleteRecursively()
	}

	/**
	 * Restore the previous verified slot using directory renames. This also recovers the interrupted
	 * state where current disappeared after being renamed to previous but before staging became current.
	 */
	@Synchronized
	fun rollback(): Boolean {
		if (!previousFile.isFile || !previousManifestFile.isFile) return false
		failedDir.deleteRecursively()
		val hadCurrent = currentDir.exists()
		if (hadCurrent && !currentDir.renameTo(failedDir)) return false
		if (!previousDir.renameTo(currentDir)) {
			if (hadCurrent) failedDir.renameTo(currentDir)
			return false
		}
		failedDir.deleteRecursively()
		return true
	}

	private fun writeSlot(
		directory: File,
		manifest: KotatsuRuntimePackManifest,
		verifier: KotatsuRuntimePackVerifier,
		writeRuntime: (File) -> Unit,
	) {
		require(directory.mkdirs() || directory.isDirectory) { "Could not create Kotatsu runtime slot" }
		val runtime = runtimeFile(directory)
		val manifestTarget = manifestFile(directory)
		writeRuntime(runtime)
		FileOutputStream(manifestTarget).use { output ->
			output.write(manifest.toJson().toByteArray(Charsets.UTF_8))
			output.fd.sync()
		}
		require(runtime.setReadOnly()) { "Could not make staged Kotatsu runtime read-only" }
		require(manifestTarget.setReadOnly()) { "Could not make staged Kotatsu manifest read-only" }
		verifier.verify(runtime, manifest).getOrThrow()
	}

	private fun runtimeFile(directory: File) = File(directory, "runtime.apk")
	private fun manifestFile(directory: File) = File(directory, "manifest.json")

	private fun readManifest(file: File): KotatsuRuntimePackManifest? =
		file.takeIf(File::isFile)?.let { runCatching { KotatsuRuntimePackManifest.parse(it.readText()) }.getOrNull() }

	private fun copyDurably(source: File, target: File) {
		source.inputStream().use { input -> copyDurably(input, target) }
	}

	private fun copyDurably(input: InputStream, target: File) {
		FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
	}

	data class RuntimeSlot(val file: File, val manifest: KotatsuRuntimePackManifest)

	private companion object {
		const val BUNDLED_APK_ASSET = "kotatsu-runtime/bundled.apk"
		const val BUNDLED_MANIFEST_ASSET = "kotatsu-runtime/manifest.json"
	}
}
