package org.koitharu.kotatsu.core.fonts

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One font file inside a [FontPack]. Mirrors one entry of the catalog index
 * (`fonts/index.json` in the Miyorare-Source-Packs repository, schema 2).
 */
data class FontPackFile(
	val file: String,
	val url: String,
	val sha256: String,
	val sizeBytes: Long,
	val family: String,
	val variable: Boolean,
	val italic: Boolean,
	val weights: List<Int>,
	val weight: Int?,
)

/** One downloadable font family from the Font Pack catalog. */
data class FontPack(
	val id: String,
	val name: String,
	val version: String,
	val author: String,
	val licenseName: String,
	val licenseUrl: String,
	val description: String,
	val previewText: String,
	val files: List<FontPackFile>,
	/** The pack's catalog entry as raw JSON, persisted next to the installed files. */
	val rawJson: String,
) {
	val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** A [FontPack] whose files are present on disk under [dir]. */
data class InstalledFontPack(
	val pack: FontPack,
	val dir: File,
)

object FontPackIndexParser {

	private val PACK_ID_REGEX = Regex("[a-z0-9]+(-[a-z0-9]+)*")

	/** Parses the catalog index (schema 2). Invalid packs are skipped, never fatal. */
	fun parse(indexJson: JSONObject): List<FontPack> {
		val packs = indexJson.optJSONArray("packs") ?: return emptyList()
		return (0 until packs.length()).mapNotNull { i ->
			runCatching { parsePack(packs.getJSONObject(i)) }.getOrNull()
		}
	}

	fun parsePack(packJson: JSONObject): FontPack? {
		val id = packJson.optString("id")
		if (!PACK_ID_REGEX.matches(id)) return null
		val filesJson = packJson.optJSONArray("files") ?: return null
		val files = (0 until filesJson.length()).mapNotNull { i ->
			runCatching { parseFile(filesJson.getJSONObject(i)) }.getOrNull()
		}
		if (files.isEmpty()) return null
		val license = packJson.optJSONObject("license")
		return FontPack(
			id = id,
			name = packJson.optString("name").ifEmpty { id },
			version = packJson.optString("version").ifEmpty { "1.0.0" },
			author = packJson.optString("author"),
			licenseName = license?.optString("name").orEmpty(),
			licenseUrl = license?.optString("url").orEmpty(),
			description = packJson.optString("description"),
			previewText = packJson.optString("previewText"),
			files = files,
			rawJson = packJson.toString(),
		)
	}

	private fun parseFile(fileJson: JSONObject): FontPackFile? {
		val file = fileJson.optString("file")
		// Installed files are addressed by plain file name only; anything with a
		// path segment is rejected so a hostile index cannot escape the pack dir.
		if (file.isEmpty() || File(file).name != file) return null
		val lower = file.lowercase()
		if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return null
		val url = fileJson.optString("url")
		if (!url.startsWith("https://")) return null
		val sha256 = fileJson.optString("sha256").lowercase()
		if (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
		val size = fileJson.optLong("sizeBytes", -1)
		if (size <= 0) return null
		val weightsJson = fileJson.optJSONArray("weights")
		val weights = if (weightsJson == null) {
			emptyList()
		} else {
			(0 until weightsJson.length()).map { weightsJson.optInt(it) }.filter { it in 1..1000 }
		}
		return FontPackFile(
			file = file,
			url = url,
			sha256 = sha256,
			sizeBytes = size,
			family = fileJson.optString("family"),
			variable = fileJson.optBoolean("variable"),
			italic = fileJson.optBoolean("italic"),
			weights = weights,
			weight = if (fileJson.has("weight")) fileJson.optInt("weight") else null,
		)
	}
}

object FontPackHashes {

	fun sha256(bytes: ByteArray): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
		return digest.joinToString("") { "%02x".format(it) }
	}

	fun sha256(file: File): String {
		val md = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read <= 0) break
				md.update(buffer, 0, read)
			}
		}
		return md.digest().joinToString("") { "%02x".format(it) }
	}
}
