package tsuki.site.madara.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.madara.MadaraParser

@MangaSourceParser("KLIKMANGA", "KlikManga", "id", ContentType.HENTAI)
internal class KlikManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.KLIKMANGA, "klikmanga.org", 36) {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/klikmanga/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}

	override val tagPrefix = "genre/"
	override val datePattern = "MMM d, yyyy"
}
