package org.koitharu.kotatsu.local.data

import android.net.Uri
import org.koitharu.kotatsu.core.model.LocalMangaSource
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

	// The canonical E-Hentai family can reuse older language-specific downloads in place. The
	// resolver never crosses the caller-provided Normal/Private root and never mutates user files.
	val legacyDirectory = EhentaiLegacyDownloadResolver.findUniqueDirectory(
		root = root,
		remoteSourceName = remoteManga.source.name,
		remoteTitle = remoteManga.title,
		remotePublicUrl = remoteManga.publicUrl,
		remoteContentUrl = remoteManga.url,
	) ?: return@runCatchingCancellable null
	val local = LocalMangaParser.getOrNull(legacyDirectory)?.getManga(withDetails)
		?: return@runCatchingCancellable null
	linkLegacyEhentaiChapter(remoteManga, local)
}.onFailure { it.printStackTraceDebug() }.getOrNull()

/**
 * Sidecar-free E-Hentai downloads contain one legacy CBZ artifact with a local-only chapter id.
 * Known forms are `Chapter.cbz` and hashed names such as `Chapter_838c38.cbz`. Miyorare Global also
 * models one gallery as one chapter, so link the remote chapter to that physical CBZ without
 * renaming it. If either side is not a single recognized legacy artifact, keep the conservative
 * local representation instead of guessing.
 */
private fun linkLegacyEhentaiChapter(remoteManga: Manga, localManga: LocalManga): LocalManga {
	val remoteChapters = remoteManga.chapters.orEmpty()
	val localChapters = localManga.manga.chapters.orEmpty()
	if (remoteChapters.size != 1 || localChapters.size != 1) {
		return localManga.copy(manga = localManga.manga.copy(id = remoteManga.id))
	}
	val localChapter = localChapters.single()
	val localChapterUri = Uri.parse(localChapter.url)
	val encodedArtifact = localChapterUri.fragment
		?.takeIf { it.isNotBlank() }
		?.substringAfterLast('/')
		?: localChapterUri.lastPathSegment.orEmpty()
	val artifactName = Uri.decode(encodedArtifact)
	if (!EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(artifactName)) {
		return localManga.copy(manga = localManga.manga.copy(id = remoteManga.id))
	}
	val linkedChapter = remoteChapters.single().copy(
		url = localChapter.url,
		source = LocalMangaSource,
	)
	return localManga.copy(
		manga = localManga.manga.copy(
			id = remoteManga.id,
			chapters = listOf(linkedChapter),
		),
	)
}
