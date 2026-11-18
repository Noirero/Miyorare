package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.site.madara.MadaraParser

@MangaSourceParser("PORNHWA18", "Pornhwa18", "id", ContentType.HENTAI)
internal class Pornhwa18(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.PORNHWA18, "pornhwa18.com") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/en/pornhwa18/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }

    override val withoutAjax = true
}
