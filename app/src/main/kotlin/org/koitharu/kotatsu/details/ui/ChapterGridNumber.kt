package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.parsers.model.MangaChapter

internal fun MangaChapter.gridNumberLabel(): String? =
	if (number.isFinite() && number >= 0f) numberString() else null
