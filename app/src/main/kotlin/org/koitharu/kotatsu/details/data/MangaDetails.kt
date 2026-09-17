package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.withMergedBranches
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.data.filterChapters
import java.util.Locale

data class MangaDetails(
    private val manga: Manga,
    private val localManga: LocalManga?,
    private val override: MangaOverride?,
    private val sourceDescription: CharSequence?,
    val isLoaded: Boolean,
) {

    /** A user-typed description always wins over whatever the source provides. */
    val description: CharSequence?
        get() = override?.description?.nullIfEmpty() ?: sourceDescription

    constructor(manga: Manga) : this(
        manga = manga,
        localManga = null,
        override = null,
        sourceDescription = null,
        isLoaded = false,
    )

    val id: Long
        get() = manga.id

    val sourceManga: Manga
        get() = manga

    val artist: String?
        get() = override?.artist?.trim()?.takeIf { it.isNotEmpty() }

    val displayDescription: CharSequence?
        get() = override?.description?.takeIf { it.isNotBlank() } ?: description ?: manga.description

    val allChapters: List<MangaChapter> by lazy { mergeChapters() }

    val chapters: Map<String?, List<MangaChapter>> by lazy {
        allChapters.groupBy { it.branch }
    }

    val isLocal
        get() = manga.isLocal

    val local: LocalManga?
        get() = localManga ?: if (manga.isLocal) LocalManga(manga) else null

    val coverUrl: String?
        get() = override?.coverUrl
            .ifNullOrEmpty { manga.coverUrl }
            .ifNullOrEmpty { localManga?.manga?.coverUrl }
            ?.nullIfEmpty()

    val backdropUrl: String?
        get() = override?.coverUrl
            .ifNullOrEmpty { manga.largeCoverUrl }
            .ifNullOrEmpty { manga.coverUrl }
            .ifNullOrEmpty { localManga?.manga?.coverUrl }
            ?.nullIfEmpty()

    val isRestricted: Boolean
        get() = manga.state == MangaState.RESTRICTED

    private val mergedManga by lazy {
        val base = if (localManga == null) {
            manga.withOverride(override)
        } else {
            manga.copy(
                title = override?.title.ifNullOrEmpty { manga.title },
                description = override?.description.ifNullOrEmpty { manga.description },
                coverUrl = override?.coverUrl.ifNullOrEmpty { manga.coverUrl },
                largeCoverUrl = override?.coverUrl.ifNullOrEmpty { manga.largeCoverUrl },
                contentRating = override?.contentRating ?: manga.contentRating,
                chapters = allChapters,
            )
        }
        base.copy(
            authors = override?.author.toAuthorsOrNull() ?: base.authors,
            description = override?.description?.takeIf { it.isNotBlank() } ?: base.description,
        )
    }

    fun toManga() = mergedManga

    fun withOverride(override: MangaOverride?) = copy(override = override)

    fun withMergedBranches() = copy(
        manga = manga.withMergedBranches(),
        localManga = localManga?.let { it.copy(manga = it.manga.withMergedBranches()) },
    )

	fun coverUrl(preferLarge: Boolean = false): String? =
		override?.coverUrl
			.ifNullOrEmpty { if (preferLarge) manga.largeCoverUrl else null }
			.ifNullOrEmpty { manga.coverUrl }
			.ifNullOrEmpty { localManga?.manga?.coverUrl }
			?.nullIfEmpty()

    fun getLocale(): Locale? {
        findAppropriateLocale(chapters.keys.singleOrNull())?.let { return it }
        return manga.source.getLocale()
    }

    fun filterChapters(branch: String?) = copy(
        manga = manga.filterChapters(branch),
        localManga = localManga?.run { copy(manga = manga.filterChapters(branch)) },
    )

    private fun mergeChapters(): List<MangaChapter> {
        val chapters = manga.chapters
        val localChapters = local?.manga?.chapters.orEmpty()
        if (chapters.isNullOrEmpty()) return localChapters
        val localMap = if (localChapters.isNotEmpty()) {
            localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
        } else null
        val result = ArrayList<MangaChapter>(chapters.size)
        for (chapter in chapters) {
            val local = localMap?.remove(chapter.id)
            result += local ?: chapter
        }
        if (!localMap.isNullOrEmpty()) result.addAll(localMap.values)
        return result
    }

    private fun findAppropriateLocale(name: String?): Locale? {
        if (name.isNullOrEmpty()) return null
        return Locale.getAvailableLocales().find { lc ->
            name.contains(lc.getDisplayName(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayName(Locale.ENGLISH), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(Locale.ENGLISH), ignoreCase = true)
        }
    }

    private fun String?.toAuthorsOrNull(): Set<String>? {
        val values = this
            ?.split(',', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        return values.takeIf { it.isNotEmpty() }
    }
}
