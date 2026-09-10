package org.koitharu.kotatsu.tsuki

import dalvik.system.DexClassLoader

/**
 * Parent-first for shared host ABI, plugin-first only for namespaces that historically lived inside
 * plugin jars. This prevents a plugin from replacing Miyorare's Tsuki/Kotatsu model classes while
 * keeping old Usagi plugins linkable.
 */
internal class TsukiPluginClassLoader(
	dexPath: String,
	optimizedDirectory: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (name.startsWith("tsuki.") || name.contains("quickjs", ignoreCase = true)) {
			return super.loadClass(name, resolve)
		}

		val isSharedLegacy = name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
			name == "org.koitharu.kotatsu.parsers.MangaParserAuthProvider" ||
			name.startsWith("org.koitharu.kotatsu.parsers.config.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.exception.") ||
			(name.startsWith("org.koitharu.kotatsu.parsers.model.") &&
				name != LEGACY_SOURCE_ENUM)
		if (isSharedLegacy) return super.loadClass(name, resolve)

		val pluginLocal = name.startsWith("org.koitharu.kotatsu.parsers.") ||
			name.startsWith("org.koitharu.kotatsu.core.parser.") ||
			name.startsWith("eu.kanade.tachiyomi.") ||
			name.startsWith("uy.kohesive.injekt.") ||
			name.startsWith("rx.") ||
			name.startsWith("keiyoushi.")
		if (pluginLocal) {
			return runCatching { findClass(name) }.getOrElse { super.loadClass(name, resolve) }
		}
		return super.loadClass(name, resolve)
	}

	companion object {
		const val MODERN_FACTORY = "tsuki.MangaParserFactoryKt"
		const val MODERN_SOURCE_ENUM = "tsuki.model.MangaParserSource"
		const val MODERN_CONTEXT = "tsuki.MangaLoaderContext"
		const val LEGACY_FACTORY = "org.koitharu.kotatsu.parsers.MangaParserFactoryKt"
		const val LEGACY_SOURCE_ENUM = "org.koitharu.kotatsu.parsers.model.MangaParserSource"
		const val LEGACY_CONTEXT = "org.koitharu.kotatsu.parsers.MangaLoaderContext"
	}
}
