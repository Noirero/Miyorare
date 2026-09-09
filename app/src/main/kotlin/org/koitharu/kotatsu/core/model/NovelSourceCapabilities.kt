package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.EnumSet

/**
 * Capabilities that can be known without starting a plugin runtime or performing network I/O.
 *
 * Keep this list intentionally small. A capability is exposed only when Miyorare can prove it from
 * source metadata/API contracts, so UI code can hide unsupported actions instead of trying them and
 * failing later. The checks are allocation-free through [supportsNovelCapability].
 */
enum class NovelSourceCapability {
	SEARCH,
	LATEST,
	FILTERS,
	PAGED_CHAPTERS,
	RESOLVE_CHAPTER_URL,
	TEXT_CONTENT,
}

fun MangaSource.supportsNovelCapability(capability: NovelSourceCapability): Boolean =
	when (val source = unwrap()) {
		is LnMangaSource -> when (capability) {
			NovelSourceCapability.SEARCH,
			NovelSourceCapability.LATEST,
			NovelSourceCapability.TEXT_CONTENT,
			-> true

			NovelSourceCapability.FILTERS -> source.plugin.filters != null
			NovelSourceCapability.PAGED_CHAPTERS -> source.plugin.hasParsePage
			NovelSourceCapability.RESOLVE_CHAPTER_URL -> source.plugin.hasResolveUrl
		}

		is MihonMangaSource -> source.isNovel && when (capability) {
			NovelSourceCapability.SEARCH,
			NovelSourceCapability.TEXT_CONTENT,
			-> true

			NovelSourceCapability.LATEST -> source.supportsLatest
			// These cannot be proven from Mihon source metadata alone without invoking extension code.
			NovelSourceCapability.FILTERS,
			NovelSourceCapability.PAGED_CHAPTERS,
			NovelSourceCapability.RESOLVE_CHAPTER_URL,
			-> false
		}

		else -> false
	}

/** Snapshot form for diagnostics/UI construction; hot paths should use [supportsNovelCapability]. */
val MangaSource.novelCapabilities: Set<NovelSourceCapability>
	get() = EnumSet.noneOf(NovelSourceCapability::class.java).apply {
		for (capability in NovelSourceCapability.values()) {
			if (supportsNovelCapability(capability)) add(capability)
		}
	}
