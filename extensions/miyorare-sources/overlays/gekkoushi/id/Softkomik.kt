package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.SOFTKOMIK, "softkomik.app", "manga") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/softkomik/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
