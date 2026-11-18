package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons

@MangaSourceParser("KOMIKDEWASAART", "Komik Dewasa.art", "id", ContentType.HENTAI)
internal class KomikDewasaART(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KOMIKDEWASAART, "komikdewasa.art") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/komikdewasaart/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }

    override val mangaDirectory = "komik"
    override val chapterListSelector = "div.bxcl li:not(:has(a[data-bs-target='#lockedChapterModal']))"
}
