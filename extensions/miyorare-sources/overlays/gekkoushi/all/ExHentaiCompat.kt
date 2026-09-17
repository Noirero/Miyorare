package tsuki.site.all

import org.jsoup.nodes.Element

/**
 * Miyorare compatibility parser for E-Hentai/ExHentai thumbnail sprites.
 *
 * Gekkoushi/Tsuki 1.0.5 compiles Element.backgroundOrNull() against
 * org.koitharu.kotatsu.core.parser.CSSBackground#getUrl(). Miyorare's pinned
 * kotatsu-parsers runtime exposes a different CSSBackground ABI, so resolving
 * bg.url from ExHentaiParser can crash with NoSuchMethodError.
 *
 * Keep the parser logic local to this source package so ExHentaiParser resolves
 * this extension before the wildcard-imported Tsuki helper. This avoids touching
 * the host parser ABI and keeps the compatibility fix isolated to this source.
 */
internal data class MiyorareCssBackground(
    val url: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

internal fun Element.backgroundOrNull(): MiyorareCssBackground? {
    val style = attr("style").trim()
    if (style.isEmpty()) return null

    val attrs = mutableMapOf<String, String>()
    for (chunk in style.split(';')) {
        val separator = chunk.indexOf(':')
        if (separator <= 0) continue
        val key = chunk.substring(0, separator).trim()
        val value = chunk.substring(separator + 1).trim()
        if (key.isNotEmpty()) {
            attrs[key] = value
        }
    }

    val width = attrs["width"]?.toCssPx() ?: return null
    val height = attrs["height"]?.toCssPx() ?: return null
    val background = attrs["background"] ?: return null
    val urlMarker = background.indexOf("url")
    if (urlMarker < 0) return null

    val parts = background
        .substring(urlMarker + 3)
        .trim()
        .split(Regex("\\s+"))
    val url = parts.firstOrNull()
        ?.removeSurrounding("(", ")")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val x = parts.getOrNull(1)?.toCssPx() ?: 0
    val y = parts.getOrNull(2)?.toCssPx() ?: 0

    return MiyorareCssBackground(
        url = url,
        left = -x,
        top = y,
        width = width,
        height = height,
    )
}

private fun String.toCssPx(): Int? = trim().removeSuffix("px").toIntOrNull()
