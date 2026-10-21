package org.koitharu.kotatsu.kotatsu.runtime

import dalvik.system.DexClassLoader

/**
 * Isolates Kotatsu-Redo's parser ABI and its fast-moving implementation dependencies from the
 * parser ABI used by Miyorare/Tsuki/Gekkoushi in the host APK.
 *
 * Android/Java/Kotlin platform classes stay parent-first. The namespaces below are deliberately
 * child-first so identical FQCNs can coexist in different class loaders without duplicate-class
 * packaging or host ABI replacement.
 */
internal class KotatsuRuntimeClassLoader(
	dexPath: String,
	optimizedDirectory: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (!isRuntimeOwned(name)) return super.loadClass(name, resolve)
		return synchronized(getClassLoadingLock(name)) {
			findLoadedClass(name)?.also { loaded ->
				if (resolve) resolveClass(loaded)
			} ?: try {
				findClass(name).also { loaded ->
					if (resolve) resolveClass(loaded)
				}
			} catch (_: ClassNotFoundException) {
				// A runtime-owned namespace may intentionally omit a dependency and inherit the host copy.
				// Only absence falls back; linkage/verification errors must remain visible and fail closed.
				super.loadClass(name, resolve)
			}
		}
	}

	private fun isRuntimeOwned(name: String): Boolean = CHILD_FIRST_PREFIXES.any(name::startsWith)

	companion object {
		internal val CHILD_FIRST_PREFIXES = listOf(
			"org.miyorare.kotatsu.runtime.",
			"org.koitharu.kotatsu.parsers.",
			"okhttp3.",
			"okio.",
			"org.jsoup.",
			"androidx.collection.",
			"kotlinx.coroutines.",
		)
	}
}
