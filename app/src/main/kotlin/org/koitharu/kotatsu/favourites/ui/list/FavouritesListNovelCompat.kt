package org.koitharu.kotatsu.favourites.ui.list

import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.parsers.model.Manga

/** Compatibility helper for call sites that need a function form of the Manga novel flag. */
internal fun isNovelContent(manga: Manga): Boolean = manga.isNovelContent
