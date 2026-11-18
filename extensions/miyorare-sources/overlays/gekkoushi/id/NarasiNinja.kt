package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*

@MangaSourceParser("NARASININJA", "NarasiNinja", "id")
internal class NarasiNinja(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.NARASININJA, "narasininja.net", "manga") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/narasininja/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
