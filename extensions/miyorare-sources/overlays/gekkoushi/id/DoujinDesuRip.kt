package tsuki.site.mangareader.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.mangareader.MangaReaderParser

@MangaSourceParser("DOUJINDESURIP", "DoujinDesu.click", "id", ContentType.HENTAI)
internal class DoujinDesuRip(context: MangaLoaderContext) :
	MangaReaderParser(
		context,
		MangaParserSource.DOUJINDESURIP,
		"doujindesu.asia",
		pageSize = 20,
		searchPageSize = 10,
	) {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/doujindesu/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}


	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
}
