package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons

@MangaSourceParser("MANHWALISTID", "ManhwaList ID", "id")
internal class ManhwaListID(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.MANHWALISTID, "m1.manhwalist.my.id") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/manhwalistid/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
