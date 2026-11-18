package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("APKOMIK", "Apkomik", "id")
internal class Apkomik(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.APKOMIK, "01.apkomik.com") {
    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/apkomik/res/mipmap-xhdpi/ic_launcher.png", 96, null),
            ),
            domain,
        )
    }

    override val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))

    override fun parseAltTitles(doc: Document, table: Element?, hasTable: Boolean): Set<String> {
        return doc.select(".wd-full:contains(Alternative) span, .wd-full:contains(Alt) span")
            .flatMap { splitAltTitles(it.text()) }
            .toSet()
    }
}
