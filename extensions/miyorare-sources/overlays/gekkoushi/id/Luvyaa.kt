package tsuki.site.mangareader.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.mangareader.MangaReaderParser

@MangaSourceParser("LUVYAA", "Luvyaa", "id", ContentType.HENTAI)
internal class Luvyaa(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LUVYAA, "v6.luvyaa.co", 20, 10) {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/Luvyaa/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}

	override val datePattern = "MMM d, yyyy"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
}
