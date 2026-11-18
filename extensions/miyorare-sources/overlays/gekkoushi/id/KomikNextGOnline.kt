package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*

@MangaSourceParser("KOMIK_NEXT_G_ONLINE", "Komik Next G Online", "id")
internal class KomikNextGOnline(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.KOMIK_NEXT_G_ONLINE, "komiknextgonline.com", "manga") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/komiknextgonline/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
