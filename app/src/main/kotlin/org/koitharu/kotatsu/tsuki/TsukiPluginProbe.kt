package org.koitharu.kotatsu.tsuki

import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import java.io.File
import java.lang.reflect.Method

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

	fun probe(file: File, optimizedDirectory: File, parent: ClassLoader): Result {
		val loader = TsukiPluginClassLoader(
			dexPath = file.absolutePath,
			optimizedDirectory = optimizedDirectory.absolutePath,
			parent = parent,
		)
		return runCatching { probeModern(loader) }.getOrElse { modernError ->
			runCatching { probeLegacy(loader) }.getOrElse { legacyError ->
				legacyError.addSuppressed(modernError)
				throw legacyError
			}
		}
	}

	private fun probeModern(loader: ClassLoader): Result {
		val factory = loader.loadClass(TsukiPluginClassLoader.MODERN_FACTORY)
		val sourceEnum = loader.loadClass(TsukiPluginClassLoader.MODERN_SOURCE_ENUM)
		val context = loader.loadClass(TsukiPluginClassLoader.MODERN_CONTEXT)
		val method = factory.getMethod("newParser", sourceEnum, context)
		val sources = sourceEnum.enumConstants.orEmpty().map { source ->
			TsukiSourceDescriptor(
				name = source.stringProperty("name") ?: error("Tsuki source has no name"),
				title = source.stringProperty("title") ?: source.stringProperty("name").orEmpty(),
				locale = source.stringProperty("locale").orEmpty(),
				contentType = source.enumNameProperty("contentType") ?: "OTHER",
				isBroken = source.booleanProperty("broken") ?: false,
			)
		}
		require(sources.isNotEmpty()) { "Plugin exposes no Tsuki sources" }
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
}
