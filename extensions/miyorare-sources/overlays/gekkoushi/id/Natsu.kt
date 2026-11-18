package tsuki.site.mangareader.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.mangareader.MangaReaderParser

@MangaSourceParser("NATSU", "Natsu", "id")
internal class Natsu(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.NATSU, "natsu.id", pageSize = 20, searchPageSize = 10) {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/natsu/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
}
