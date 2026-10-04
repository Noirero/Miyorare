package org.koitharu.kotatsu.local.library

import android.util.Xml
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Optional enrichment only. Limits and disabled declarations keep user XML out of the trust path. */
internal data class LocalMetadata(
    val title: String? = null, val authors: Set<String> = emptySet(), val description: String? = null,
    val coverName: String? = null,
) {
    companion object {
        fun read(node: Node, documents: LocalDocuments): LocalMetadata = runCatching {
            documents.input(node).use { input ->
                if (node.name.equals("index.json", true)) {
                    readJson(input)
                } else readXml(input)
            }
        }.getOrDefault(LocalMetadata())

        fun readJson(input: InputStream): LocalMetadata {
            val index = MangaIndex(readBounded(input))
            val manga = index.getMangaInfo()
            return LocalMetadata(manga?.title, manga?.authors.orEmpty(), manga?.description, index.getCoverEntry())
        }

        fun readXml(input: InputStream): LocalMetadata {
            val text = readBounded(input)
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
            val parser = Xml.newPullParser()
            parser.setInput(text.reader())
            val values = HashMap<String, String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val key = parser.name.orEmpty().substringAfterLast(':').lowercase()
                    if (key in setOf("series", "title", "writer", "author", "summary", "description", "cover")) {
                        values[key] = parser.nextText().trim()
                    }
                }
            }
            return LocalMetadata(values["series"]?.takeIf { it.isNotBlank() } ?: values["title"]?.takeIf { it.isNotBlank() },
                (values["writer"] ?: values["author"]).orEmpty().split(',').map { it.trim() }
                    .filterTo(LinkedHashSet()) { it.isNotBlank() },
                values["summary"] ?: values["description"], values["cover"])
        }

        private fun readBounded(input: InputStream): String {
            val bytes = input.readBytesLimited(512 * 1024)
            return bytes.toString(Charsets.UTF_8)
        }
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(output.size().toLong() + read <= limit) { "Local content exceeds size limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
