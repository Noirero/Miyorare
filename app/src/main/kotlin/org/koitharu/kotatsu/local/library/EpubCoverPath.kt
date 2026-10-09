package org.koitharu.kotatsu.local.library

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder

/** Bounded container/OPF cover selection only. No spine, TOC or Reader materialization. */
internal object EpubCoverPath {
    fun opf(container: ByteArray): String? = elements(container).firstOrNull { it.localTagName() == "rootfile" }
        ?.localAttributeValue("full-path")?.let { resolve("", it) }

    fun cover(opf: String, metadata: ByteArray): String? {
        val elements = elements(metadata)
        val items = elements.filter { it.localTagName() == "item" }
        val id = elements.firstOrNull { it.localTagName() == "meta" && it.localAttributeValue("name") == "cover" }?.localAttributeValue("content")
        val selected = id?.let { value -> items.firstOrNull { it.localAttributeValue("id") == value } }
            ?: items.firstOrNull { "cover-image" in it.localAttributeValue("properties").orEmpty().split(Regex("\\s+")) }
            ?: items.firstOrNull { it.localAttributeValue("media-type").orEmpty().startsWith("image/") &&
                (it.localAttributeValue("id").orEmpty().contains("cover", true) || it.localAttributeValue("href").orEmpty().contains("cover", true)) }
        return selected?.localAttributeValue("href")?.let { resolve(opf.substringBeforeLast('/', ""), it) }
    }

    internal fun resolve(base: String, href: String): String? {
        val decoded = try { URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8") }
        catch (_: IllegalArgumentException) { return null }
        if (decoded.isBlank() || decoded.startsWith('/') || ':' in decoded || '\\' in decoded || '\u0000' in decoded) return null
        val segments = base.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in decoded.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += part
        }
        return segments.joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun elements(bytes: ByteArray): List<Element> = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser()).allElements.toList()
    private fun Element.localTagName() = tagName().substringAfterLast(':').lowercase()
    private fun Element.localAttributeValue(name: String) = attributes().firstOrNull { it.key.substringAfterLast(':') == name }?.value
}
