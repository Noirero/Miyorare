package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*

@MangaSourceParser("DREAMTEAMS_SCANS", "DreamTeams Scans", "id", ContentType.HENTAI)
internal class DreamTeamsScans(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.DREAMTEAMS_SCANS, "dreamteams.space", "comic") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/dreamteamsscans/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }
}
