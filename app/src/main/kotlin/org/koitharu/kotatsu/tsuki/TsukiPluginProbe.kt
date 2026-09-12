package org.koitharu.kotatsu.tsuki

import org.json.JSONObject
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import java.io.File
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.zip.ZipFile

/** Links only the plugin ABI and enumerates lightweight source metadata; it never creates parsers. */
internal object TsukiPluginProbe {

	enum class Abi {
		TSUKI_1,
		LEGACY_KOTATSU,
	}

	data class Result(
		val abi: Abi,
		val factoryMethod: Method,
		val sources: List<TsukiSourceDescriptor>,
	)

	private data class MiyorareSourceMetadata(
		val sourceNames: Set<String>,
		val sourceIcons: Map<String, String>,
	)

	fun probe(file: File, optimizedDirectory: File, parent: ClassLoader): Result {
		val sourceMetadata = readMiyorareSourceMetadata(file)
		val loader = TsukiPluginClassLoader(
			dexPath = file.absolutePath,
			optimizedDirectory = optimizedDirectory.absolutePath,
			parent = parent,
		)
		return runCatching { probeModern(loader, sourceMetadata) }.getOrElse { modernError ->
			// Detect old Kotatsu/Usagi plugin jars only to return a deterministic compatibility error.
			// Runtime execution intentionally supports Tsuki 1.0.x only; accepting a legacy jar here
			// would make installation appear successful and then fail when the source is opened.
			val legacy = runCatching { probeLegacy(loader) }
			if (legacy.isSuccess) {
				throw IllegalArgumentException(
					"Legacy Kotatsu plugin ABI is not supported. Install a Tsuki 1.0.x plugin release.",
					modernError,
				)
			}
			val legacyError = legacy.exceptionOrNull()
			if (legacyError != null) modernError.addSuppressed(legacyError)
			throw modernError
		}
	}

	private fun probeModern(loader: ClassLoader, metadata: MiyorareSourceMetadata?): Result {
		val factory = loader.loadClass(TsukiPluginClassLoader.MODERN_FACTORY)
		val sourceEnum = loader.loadClass(TsukiPluginClassLoader.MODERN_SOURCE_ENUM)
		val context = loader.loadClass(TsukiPluginClassLoader.MODERN_CONTEXT)
		val method = factory.getMethod("newParser", sourceEnum, context)
		val compiledSources = sourceEnum.enumConstants.orEmpty().map { source ->
			TsukiSourceDescriptor(
				name = source.stringProperty("name") ?: error("Tsuki source has no name"),
				title = source.stringProperty("title") ?: source.stringProperty("name").orEmpty(),
				locale = source.stringProperty("locale").orEmpty(),
				contentType = source.enumNameProperty("contentType") ?: "OTHER",
				isBroken = source.booleanProperty("broken") ?: false,
			)
		}
		require(compiledSources.isNotEmpty()) { "Plugin exposes no Tsuki sources" }

		val filtered = if (metadata == null) {
			compiledSources
		} else {
			val compiledNames = compiledSources.asSequence().map { it.name }.toSet()
			val missing = metadata.sourceNames - compiledNames
			require(missing.isEmpty()) {
				"Miyorare source-pack metadata references missing sources: ${missing.sorted().joinToString()}"
			}
			compiledSources.filter { it.name in metadata.sourceNames }
		}
		val sources = if (metadata == null || metadata.sourceIcons.isEmpty()) {
			filtered
		} else {
			filtered.map { source ->
				source.copy(iconUrl = metadata.sourceIcons[source.name])
			}
		}
		require(sources.isNotEmpty()) { "Plugin exposes no enabled Tsuki sources" }
		return Result(Abi.TSUKI_1, method, sources)
	}

	private fun probeLegacy(loader: ClassLoader): Result {
		val factory = loader.loadClass(TsukiPluginClassLoader.LEGACY_FACTORY)
		val sourceEnum = loader.loadClass(TsukiPluginClassLoader.LEGACY_SOURCE_ENUM)
		val context = loader.loadClass(TsukiPluginClassLoader.LEGACY_CONTEXT)
		val method = factory.getMethod("newParser", sourceEnum, context)
		val sources = sourceEnum.enumConstants.orEmpty().map { source ->
			TsukiSourceDescriptor(
				name = source.stringProperty("name") ?: error("Legacy source has no name"),
				title = source.stringProperty("title") ?: source.stringProperty("name").orEmpty(),
				locale = source.stringProperty("locale").orEmpty(),
				contentType = source.enumNameProperty("contentType") ?: "OTHER",
				isBroken = source.booleanProperty("broken") ?: false,
			)
		}
		require(sources.isNotEmpty()) { "Plugin exposes no legacy sources" }
		return Result(Abi.LEGACY_KOTATSU, method, sources)
	}

	/**
	 * Official Miyorare packs embed an exposed source allowlist. Schema 1 is the legacy one-JAR
	 * release format; schema 2 is the independent UMA/Gekkoushi shard format. Schema 2 may also
	 * carry optional sourceIcons generated from upstream source metadata. Ordinary third-party
	 * plugins have no metadata entry and retain the existing behavior of exposing every enum constant.
	 */
	private fun readMiyorareSourceMetadata(file: File): MiyorareSourceMetadata? = ZipFile(file).use { archive ->
		val entry = archive.getEntry(MIYORARE_PACK_METADATA) ?: return@use null
		val root = archive.getInputStream(entry).bufferedReader().use { reader ->
			JSONObject(reader.readText())
		}
		val schema = root.optInt("schema", 0)
		require(schema == 1 || schema == 2) { "Unsupported Miyorare source-pack metadata schema" }
		val array = root.optJSONArray("sourceNames")
			?: error("Miyorare source-pack metadata has no sourceNames")
		val names = LinkedHashSet<String>(array.length())
		for (index in 0 until array.length()) {
			val name = array.optString(index).trim()
			require(name.isNotEmpty()) { "Miyorare source-pack metadata contains an empty source name" }
			require(names.add(name)) { "Miyorare source-pack metadata contains duplicate source $name" }
		}
		require(names.isNotEmpty()) { "Miyorare source-pack metadata contains no exposed sources" }

		val iconsJson = root.optJSONObject("sourceIcons")
		val icons = LinkedHashMap<String, String>()
		if (iconsJson != null) {
			for (key in iconsJson.keys()) {
				require(key in names) { "Miyorare source-pack icon metadata references unknown source $key" }
				val url = iconsJson.optString(key).trim()
				if (url.isEmpty()) continue
				require(url.startsWith("https://") || url.startsWith("http://")) {
					"Miyorare source-pack icon URL must use HTTP(S): $key"
				}
				require(url.length <= MAX_ICON_URL_LENGTH) { "Miyorare source-pack icon URL is too long: $key" }
				icons[key] = url
			}
		}
		MiyorareSourceMetadata(names, icons)
	}

	private fun Any.stringProperty(name: String): String? =
		invokeGetter(name) as? String

	private fun Any.enumNameProperty(name: String): String? =
		(invokeGetter(name) as? Enum<*>)?.name

	private fun Any.booleanProperty(name: String): Boolean? =
		(invokeGetter(name) as? Boolean)

	private fun Any.invokeGetter(name: String): Any? {
		val suffix = name.replaceFirstChar { it.uppercaseChar() }
		val candidates = listOf("get$suffix", "is$suffix")
		return candidates.firstNotNullOfOrNull { methodName ->
			runCatching { javaClass.getMethod(methodName).invoke(this) }.getOrNull()
		}
	}

	private const val MIYORARE_PACK_METADATA = "META-INF/miyorare-pack.json"
	private const val MAX_ICON_URL_LENGTH = 2_048
}