package eu.kanade.tachiyomi.source

/**
 * Compatibility marker for novel extensions compiled against source-api 1.6.
 *
 * Newer extensions may implement this marker without overriding [Source.isNovelSource], while
 * older extensions commonly override the property as well. Hosts therefore treat either signal as
 * authoritative and continue to fetch prose through [Source.fetchPageText].
 */
@Deprecated("Use Source.isNovelSource() for compatibility detection; fetchPageText is on Source")
interface NovelSource : Source

fun Source.isNovelSource(): Boolean = this is NovelSource || isNovelSource
