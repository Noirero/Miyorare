package tsuki.site.madara.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

/**
 * Miyorare compatibility overlay for NovelCrow.
 *
 * Keiyoushi's NovelCrow fix (c66a8026cf1d8df0f52e98b7b5659f1daf5727e4) moved catalogue
 * requests away from Madara AJAX, used the comic route, and removed the reader style suffix.
 * Gekkoushi's pinned parser still uses the generic AJAX/style defaults, while Track B Batch 3
 * reproduced browse failure on both candidate and LKG. Keep this overlay minimal and let the
 * real-parser Track B harness prove whether the upstream behavior is restored.
 */
@MangaSourceParser("NOVELCROW", "NovelCrow", "en", ContentType.HENTAI)
internal class Novelcrow(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.NOVELCROW, "novelcrow.com", 24) {
    override val withoutAjax = true
    override val stylePage = ""
    override val tagPrefix = "comic-genre/"
}
