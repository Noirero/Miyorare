package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.site.zmanga.ZMangaParser

@MangaSourceParser("CROTPEDIA", "CrotPedia", "id", ContentType.HENTAI)
internal class CrotPedia(context: MangaLoaderContext) :
    ZMangaParser(context, MangaParserSource.CROTPEDIA, "crotpedia.net") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/crotpedia/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
