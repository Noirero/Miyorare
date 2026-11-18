package tsuki.site.animebootstrap.id

import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.animebootstrap.AnimeBootstrapParser

@Broken("Chapter images are served behind the site's Cloudflare wall and come back as HTML; lists and details load, reading is broken")
@MangaSourceParser("SEKTEKOMIK", "SekteKomik", "id")
internal class SekteKomik(context: MangaLoaderContext) :
	AnimeBootstrapParser(context, MangaParserSource.SEKTEKOMIK, "01.sektekomik.id")
