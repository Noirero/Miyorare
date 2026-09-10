package org.koitharu.kotatsu.tsuki

import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityResult
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipException

/** Validation that is deliberately completed before any plugin class is loaded. */
object TsukiPluginValidator {

	const val HOST_API_VERSION = "1.0.5"
	private const val MAX_PLUGIN_BYTES = 128L * 1024L * 1024L
	private const val MAX_ARCHIVE_ENTRIES = 20_000

	data class ValidatedJar(
		val sha256: String,
		val fileSize: Long,
		val lastModified: Long,
		val declaredApi: String?,
		val declaredVersion: String?,
	)

	fun validate(file: File): Result<ValidatedJar> = runCatching {
		require(file.isFile) { "Plugin file does not exist" }
		require(file.extension.equals("jar", ignoreCase = true)) { "Plugin must be a .jar file" }
		require(file.length() in 1..MAX_PLUGIN_BYTES) { "Plugin file size is outside the allowed range" }

		val manifestData = try {
			JarFile(file, false).use { jar ->
				var count = 0
				var hasDex = false
				val entries = jar.entries()
				while (entries.hasMoreElements()) {
					val entry = entries.nextElement()
					count++
					require(count <= MAX_ARCHIVE_ENTRIES) { "Plugin archive contains too many entries" }
					val name = entry.name.replace('\\', '/')
					require(!name.startsWith('/') && !name.split('/').any { it == ".." }) {
						"Plugin archive contains an unsafe path"
					}
					require(!name.endsWith(".so", ignoreCase = true)) {
						"Native libraries are not accepted in Tsuki plugins"
					}
					if (name.matches(Regex("classes(\\d*)\\.dex"))) hasDex = true
				}
				require(hasDex) { "Plugin is not a dexed JAR" }
				val attributes = jar.manifest?.mainAttributes
				Pair(
					attributes?.getValue("Tsuki-Api-Version")
						?: attributes?.getValue("Tsuki-Version")
						?: attributes?.getValue("Plugin-Api-Version"),
					attributes?.getValue("Implementation-Version")
						?: attributes?.getValue("Plugin-Version"),
				)
			}
		} catch (e: ZipException) {
			throw IllegalArgumentException("Invalid plugin archive", e)
		}

		ValidatedJar(
			sha256 = sha256(file),
			fileSize = file.length(),
			lastModified = file.lastModified(),
			declaredApi = manifestData.first?.trim()?.takeIf { it.isNotEmpty() },
			declaredVersion = manifestData.second?.trim()?.takeIf { it.isNotEmpty() },
		)
	}

	/**
	 * Unknown API metadata is allowed only after the compatibility probe successfully links the
	 * plugin against the pinned host ABI. Declared versions newer than the host are rejected first.
	 */
	fun compatibility(requiredApi: String?, probeSucceeded: Boolean, probeError: String? = null): TsukiCompatibilityResult {
		if (requiredApi != null && !isSupportedVersion(requiredApi)) {
			return TsukiCompatibilityResult(
				status = TsukiCompatibilityStatus.INCOMPATIBLE,
				hostApi = HOST_API_VERSION,
				requiredApi = requiredApi,
				reason = "Plugin requires unsupported Tsuki API $requiredApi",
			)
		}
		return if (probeSucceeded) {
			TsukiCompatibilityResult(
				status = TsukiCompatibilityStatus.COMPATIBLE,
				hostApi = HOST_API_VERSION,
				requiredApi = requiredApi,
			)
		} else {
			TsukiCompatibilityResult(
				status = TsukiCompatibilityStatus.INCOMPATIBLE,
				hostApi = HOST_API_VERSION,
				requiredApi = requiredApi,
				reason = probeError ?: "Plugin ABI could not be linked against Tsuki $HOST_API_VERSION",
			)
		}
	}

	private fun isSupportedVersion(value: String): Boolean {
		val required = parseVersion(value) ?: return false
		val host = parseVersion(HOST_API_VERSION) ?: return false
		return required.first == host.first && required.second == host.second && required.third <= host.third
	}

	private fun parseVersion(value: String): Triple<Int, Int, Int>? {
		val numbers = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(value)?.groupValues ?: return null
		return Triple(
			numbers[1].toIntOrNull() ?: return null,
			numbers[2].toIntOrNull() ?: return null,
			numbers.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
		)
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
		return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
	}
}
