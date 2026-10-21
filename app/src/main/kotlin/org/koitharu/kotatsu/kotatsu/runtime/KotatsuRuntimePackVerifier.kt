package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Fail-closed validation performed before any class from a downloaded runtime pack is loaded. */
internal class KotatsuRuntimePackVerifier(private val context: Context) {

	fun verify(file: File, manifest: KotatsuRuntimePackManifest): Result<Unit> = runCatching {
		require(manifest.isHostCompatible) {
			"Runtime protocol ${manifest.protocolVersion} is not supported by host ${KotatsuRuntimePackContract.PROTOCOL_VERSION}"
		}
		require(file.isFile) { "Kotatsu runtime pack does not exist" }
		require(file.length() in 1..KotatsuRuntimePackContract.MAX_PACK_BYTES) { "Kotatsu runtime pack size is invalid" }
		require(sha256(file).equals(manifest.sha256, ignoreCase = true)) { "Kotatsu runtime pack checksum mismatch" }

		val pm = context.packageManager
		val archive = pm.getPackageArchiveInfo(file.absolutePath, signatureFlags())
			requireNotNull(archive) { "Kotatsu runtime pack is not a valid APK" }
		val host = pm.getPackageInfo(context.packageName, signatureFlags())
		val archiveSigners = signerDigests(archive)
		val hostSigners = signerDigests(host)
		require(archiveSigners.isNotEmpty() && archiveSigners == hostSigners) {
			"Kotatsu runtime pack is not signed by the Miyorare signing identity"
		}
	}

	@Suppress("DEPRECATION")
	private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		PackageManager.GET_SIGNING_CERTIFICATES
	} else {
		PackageManager.GET_SIGNATURES
	}

	@Suppress("DEPRECATION")
	private fun signerDigests(info: PackageInfo): Set<String> {
		val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val signingInfo = info.signingInfo ?: return emptySet()
			if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
		} else {
			info.signatures.orEmpty()
		}
		return signatures.mapTo(linkedSetOf()) { signature ->
			MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
		}
	}

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read <= 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().toHex()
	}

	private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
