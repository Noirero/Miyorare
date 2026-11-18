package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource
import tsuki.model.ContentType
import tsuki.model.Favicon
import tsuki.model.Favicons

@MangaSourceParser("SEKTEDOUJIN", "SekteDoujin", "id", ContentType.HENTAI)
internal class SekteDoujin(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.SEKTEDOUJIN, "sektedoujin.cc") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/sektedoujin/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}