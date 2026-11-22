package org.koitharu.kotatsu.sources.compat

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only, bounded gallery identity evidence for imported E-Hentai chapter CBZs.
 *
 * A sidecar applies only when its folder contains exactly one CBZ. An XML claim is useful for
 * matching a remote gallery for reading; it is never a receipt or authorization to delete files.
 */
internal object EhentaiComicInfo {
    sealed interface Evidence {
        data class Gallery(val id: Long) : Evidence
        data object Absent : Evidence
        data object Invalid : Evidence
    }

    private const val MAX_XML_BYTES = 512 * 1024
    private const val XML_NAME = "ComicInfo.xml"

    fun inspect(chapterFile: File): Evidence {
        if (!chapterFile.isFile || !chapterFile.extension.equals("cbz", ignoreCase = true)) {
            return Evidence.Invalid
        }
        val internal = try {
            ZipFile(chapterFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.equals(XML_NAME, ignoreCase = true) }
                    .take(2).toList()
                when (entries.size) {
                    0 -> Evidence.Absent
                    1 -> {
                        val entry = entries.single()
                        if (entry.size > MAX_XML_BYTES) Evidence.Invalid
                        else zip.getInputStream(entry).use(::parse)
                    }
                    else -> Evidence.Invalid
                }
            }
        } catch (_: Exception) {
            Evidence.Invalid
        }
        if (internal != Evidence.Absent) return internal

        val directory = chapterFile.parentFile ?: return Evidence.Absent
        val archives = directory.listFiles { file ->
            file.isFile && file.extension.equals("cbz", ignoreCase = true)
        } ?: return Evidence.Absent
        if (archives.size != 1 || archives.single().canonicalFile != chapterFile.canonicalFile) {
            return Evidence.Absent
        }
        val sidecars = directory.listFiles { file ->
            file.isFile && file.name.equals(XML_NAME, ignoreCase = true)
        } ?: return Evidence.Absent
        return when (sidecars.size) {
            0 -> Evidence.Absent
            1 -> try {
                sidecars.single().inputStream().use(::parse)
            } catch (_: Exception) {
                Evidence.Invalid
            }
            else -> Evidence.Invalid
        }
    }

    private fun parse(input: InputStream): Evidence {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_XML_BYTES) return Evidence.Invalid
            bytes.write(buffer, 0, read)
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                isXIncludeAware = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes.toByteArray()))
            val root = document.documentElement ?: return Evidence.Invalid
            if (!root.nodeName.substringAfter(':').equals("ComicInfo", ignoreCase = true)) {
                return Evidence.Invalid
            }
            val websites = ArrayList<String>(1)
            val children = root.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element && child.nodeName.substringAfter(':').equals("Web", ignoreCase = true)) {
                    websites += child.textContent.orEmpty().trim()
                }
            }
            val web = websites.singleOrNull()?.takeIf(String::isNotBlank) ?: return Evidence.Invalid
            val uri = URI(web)
            val scheme = uri.scheme
            if (scheme == null) {
                if (!web.startsWith("/g/")) return Evidence.Invalid
            } else if ((!scheme.equals("http", true) && !scheme.equals("https", true)) ||
                uri.host.isNullOrBlank() || uri.userInfo != null
            ) {
                // An absolute URL needs a real official host. URI("http:/g/123") has no
                // host, and must never be accepted as a relative gallery path.
                return Evidence.Invalid
            }
            val id = EhentaiSourceFamily.galleryId(web)?.takeIf { it > 0 }
                ?: return Evidence.Invalid
            Evidence.Gallery(id)
        } catch (_: Exception) {
            Evidence.Invalid
        }
    }
}
