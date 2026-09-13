package org.koitharu.kotatsu.local.data

import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import java.io.File

/**
 * Resolve a downloaded copy only inside [root].
 *
 * This intentionally bypasses the global LocalMangaIndex because one remote manga may now have a
 * Normal copy and a Private copy at the same time. The global index remains useful for legacy/global
 * discovery, while a favourites-scoped screen must not let the most recently indexed copy decide
 * which physical download is opened or deleted.
 */
suspend fun LocalMangaRepository.findSavedMangaInRoot(
	remoteManga: Manga,
	root: File,
	withDetails: Boolean = true,
): LocalManga? = runCatchingCancellable {
	val output = LocalMangaOutput.get(root, remoteManga)
	if (output != null) {
		val local = try {
			LocalMangaParser.getOrNull(output.rootFile)?.getManga(withDetails)
		} finally {
			output.close()
		}
		if (local != null) return@runCatchingCancellable local
	}

	// Miyorare Global writes new ExHentai downloads to ExHentai (OTHER), but older E-Hentai
	// downloads may still live in E-Hentai (ALL)/(EN). Reuse a unique legacy Chapter.cbz in place;
	// never move/copy/rename it and never cross the caller-provided Normal/Private root.
	val legacyDirectory = EhentaiLegacyDownloadResolver.findUniqueDirectory(
		root = root,
		remoteSourceName = remoteManga.source.name,
		remoteTitle = remoteManga.title,
	) ?: return@runCatchingCancellable null
	LocalMangaParser.getOrNull(legacyDirectory)?.getManga(withDetails)
}.onFailure { it.printStackTraceDebug() }.getOrNull()
