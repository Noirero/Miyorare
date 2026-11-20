package org.koitharu.kotatsu.core.icons

import org.json.JSONObject
import java.io.File

/**
 * One glyph inside an [IconPack]. Mirrors one entry of the catalog index
 * (`icons/index.json` in the Miyorare-Source-Packs repository, schema 1).
 * The [key] is the name of the built-in drawable this glyph replaces
 * (for example `ic_appearance`); settings icons the pack does not cover
 * keep their built-in artwork.
 */
data class IconPackFile(
	val key: String,
	val url: String,
	val sha256: String,
	val sizeBytes: Long,
) {
	/** On-disk SVG file name for this glyph, derived from the validated key. */
	val svgFileName: String get() = "$key.svg"

	/** On-disk rendered bitmap file name for this glyph. */
	val pngFileName: String get() = "$key.png"
}

/** One downloadable settings icon pack from the Icon Pack catalog. */
data class IconPack(
	val id: String,
	val name: String,
	val version: String,
	val licenseName: String,
	val description: String,
	val files: List<IconPackFile>,
	/** The pack's catalog entry as raw JSON, persisted next to the installed files. */
	val rawJson: String,
) {
	val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** An [IconPack] whose files are present on disk under [dir]. */
data class InstalledIconPack(
	val pack: IconPack,
	val dir: File,
)

object IconPackIndexParser {

	private val PACK_ID_REGEX = Regex("[a-z0-9]+(-[a-z0-9]+)*")
	private val ICON_KEY_REGEX = Regex("ic_[a-z0-9_]+")

	/** Parses the catalog index (schema 1). Invalid packs are skipped, never fatal. */
	fun parse(indexJson: JSONObject): List<IconPack> {
		val packs = indexJson.optJSONArray("packs") ?: return emptyList()
		return (0 until packs.length()).mapNotNull { i ->
			runCatching { parsePack(packs.getJSONObject(i)) }.getOrNull()
		}
	}

	fun parsePack(packJson: JSONObject): IconPack? {
		val id = packJson.optString("id")
		if (!PACK_ID_REGEX.matches(id)) return null
		val filesJson = packJson.optJSONArray("files") ?: return null
		val files = (0 until filesJson.length()).mapNotNull { i ->
			runCatching { parseFile(filesJson.getJSONObject(i)) }.getOrNull()
		}
		if (files.isEmpty()) return null
		return IconPack(
			id = id,
			name = packJson.optString("name").ifEmpty { id },
			version = packJson.optString("version").ifEmpty { "1.0.0" },
			licenseName = packJson.optString("license"),
			description = packJson.optString("description"),
			files = files,
			rawJson = packJson.toString(),
		)
	}

	private fun parseFile(fileJson: JSONObject): IconPackFile? {
		val key = fileJson.optString("key")
		// Keys double as on-disk file names, so they must be plain drawable
		// names: no path segments, no dots, nothing exotic.
		if (!ICON_KEY_REGEX.matches(key)) return null
		val url = fileJson.optString("url")
		if (!url.startsWith("https://")) return null
		val sha256 = fileJson.optString("sha256").lowercase()
		if (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
		val size = fileJson.optLong("sizeBytes", -1)
		if (size <= 0) return null
		return IconPackFile(
			key = key,
			url = url,
			sha256 = sha256,
			sizeBytes = size,
		)
	}
}

/**
 * Shape and safety gate for a downloaded glyph. Pack icons are static,
 * single-color 24x24 SVG paths; anything that could script, phone home, or
 * pull in external content is rejected before the file is rendered.
 */
object IconPackSvg {

	private val BANNED_TOKENS = listOf(
		"<script",
		"foreignobject",
		"<image",
		"<use",
		"href",
		"url(",
		"<!doctype",
		"<!entity",
		"onload",
		"onclick",
		"onerror",
	)

	fun isSafeGlyph(svgText: String): Boolean {
		val text = svgText.trim()
		if (!text.startsWith("<svg")) return false
		if (!text.contains("viewBox=\"0 0 24 24\"")) return false
		val lower = text.lowercase()
		return BANNED_TOKENS.none { it in lower }
	}
}
