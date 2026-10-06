package org.koitharu.kotatsu.core.cache

import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource

internal fun MangaSource.pageMetadataFingerprint(): String = when (this) {
	is LnMangaSource -> "ln:${plugin.version}"
	is TsukiMangaSource -> "tsuki:${plugin.version}:${plugin.sha256}"
	is MihonMangaSource -> "mihon:$pkgName"
	else -> "native:${BuildConfig.VERSION_CODE}"
}
