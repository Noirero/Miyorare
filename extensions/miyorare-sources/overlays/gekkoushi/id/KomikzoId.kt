package tsuki.site.animebootstrap.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.animebootstrap.AnimeBootstrapParser

@MangaSourceParser("KOMIKZOID", "KomikzoId", "id")
internal class KomikzoId(context: MangaLoaderContext) :
	AnimeBootstrapParser(context, MangaParserSource.KOMIKZOID, "komikzoid.id") {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/komikzoid/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}
}
