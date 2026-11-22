package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EhentaiComicInfoTest {
    @Test fun internalXmlIdentifiesAnOfficialGalleryWithoutChangingTheArchive() = withRoot { root ->
        val file = cbz(root, xml = xml("https://e-hentai.org/g/3863867/token/?nw=always"))
        val before = file.readBytes()
        assertEquals(EhentaiComicInfo.Evidence.Gallery(3863867L), EhentaiComicInfo.inspect(file))
        assertEquals(before.toList(), file.readBytes().toList())
    }

    @Test fun absentXmlKeepsLegacyCbzUnidentified() = withRoot { root ->
        assertEquals(EhentaiComicInfo.Evidence.Absent, EhentaiComicInfo.inspect(cbz(root)))
    }

    @Test fun uniqueAdjacentComicInfoIsAssociatedOnlyWithItsOneCbz() = withRoot { root ->
        val single = cbz(root)
        File(root, "ComicInfo.xml").writeText(xml("https://exhentai.org/g/23456/token/"))
        assertEquals(EhentaiComicInfo.Evidence.Gallery(23456L), EhentaiComicInfo.inspect(single))
        cbz(root, "Chapter_abc123.cbz")
        assertEquals(EhentaiComicInfo.Evidence.Absent, EhentaiComicInfo.inspect(single))
    }

    @Test fun foreignHostAndNonHttpUrlsAreRejected() = withRoot { root ->
        val external = cbz(root, xml = xml("https://malicious.example/g/123/token"))
        assertEquals(EhentaiComicInfo.Evidence.Invalid, EhentaiComicInfo.inspect(external))
        val file = cbz(root, "Chapter2.cbz", xml("file:///g/123/token"))
        assertEquals(EhentaiComicInfo.Evidence.Invalid, EhentaiComicInfo.inspect(file))
    }

    @Test fun duplicateAndMaliciousXmlAreRejected() = withRoot { root ->
        val duplicate = cbz(root, xml = xml("https://e-hentai.org/g/123/token"), extraXml = xml("https://e-hentai.org/g/124/token"))
        assertEquals(EhentaiComicInfo.Evidence.Invalid, EhentaiComicInfo.inspect(duplicate))
        val dtd = cbz(root, "Chapter2.cbz", "<!DOCTYPE ComicInfo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><ComicInfo><Web>&x;</Web></ComicInfo>")
        assertEquals(EhentaiComicInfo.Evidence.Invalid, EhentaiComicInfo.inspect(dtd))
    }

    @Test fun oversizedXmlFailsClosed() = withRoot { root ->
        val large = cbz(root, xml = xml("https://e-hentai.org/g/123/token", "x".repeat(600_000)))
        assertEquals(EhentaiComicInfo.Evidence.Invalid, EhentaiComicInfo.inspect(large))
    }

    private fun xml(url: String, padding: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ComicInfo><Series>Gallery</Series><Web>$url</Web><Summary>$padding</Summary></ComicInfo>"

    private fun cbz(root: File, name: String = "Chapter.cbz", xml: String? = null, extraXml: String? = null): File =
        File(root, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("1.webp"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                if (xml != null) {
                    zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zip.write(xml.toByteArray())
                    zip.closeEntry()
                }
                if (extraXml != null) {
                    zip.putNextEntry(ZipEntry("comicinfo.XML"))
                    zip.write(extraXml.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ehentai-comicinfo").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
