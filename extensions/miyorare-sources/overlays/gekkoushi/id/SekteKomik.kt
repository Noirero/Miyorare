package tsuki.site.animebootstrap.id

import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.site.animebootstrap.AnimeBootstrapParser

@Broken("Chapter images are served behind the site's Cloudflare wall and come back as HTML; lists and details load, reading is broken")
@MangaSourceParser("SEKTEKOMIK", "SekteKomik", "id")
internal class SekteKomik(context: MangaLoaderContext) :
	AnimeBootstrapParser(context, MangaParserSource.SEKTEKOMIK, "01.sektekomik.id") {
	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/id/sektekomik/res/mipmap-xhdpi/ic_launcher.png", 96, null),
			),
			domain,
		)
	}
}
