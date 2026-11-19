package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.DoujinDesuParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons

@MangaSourceParser("DOUJINDESU", "Doujindesu", "id", ContentType.HENTAI)
internal class DoujinDesu(context: MangaLoaderContext) :
    DoujinDesuParser(context, MangaParserSource.DOUJINDESU) {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/doujindesu/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
