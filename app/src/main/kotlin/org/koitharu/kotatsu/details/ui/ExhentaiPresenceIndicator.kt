package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterFileName
import java.io.File

internal fun supportsExhentaiPresenceIndicator(source: String): Boolean =
    source == EhentaiSourceFamily.OFFICIAL_SOURCE_NAME || source == "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"

private val collisionChapterName = Regex("^Chapter \\([0-9]+\\)\\.cbz$", RegexOption.IGNORE_CASE)

/** Conservative, filesystem-free association for the standard online chapter only.
 * Renamed or unrecognized archives retain the independent manga presence indicator.
 */
internal fun isStandardExhentaiPresencePath(path: String): Boolean {
    val name = File(path).name
    return EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(name) ||
        ExhentaiChapterFileName.explicitGalleryId(name) != null ||
        collisionChapterName.matches(name)
}
