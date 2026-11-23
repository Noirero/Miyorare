package org.koitharu.kotatsu.favourites.domain

import java.io.File
import java.nio.file.Files
import org.junit.Test

internal class ExhentaiPresenceScannerRegressionTest {
    @Test fun manualCbzPresenceIsIsolatedAndDoesNotRequireValidArchive() {
        val tmp = Files.createTempDirectory("exhentai-presence").toFile()
        try {
            val normal = File(tmp, "normal").apply { mkdirs() }
            val privateRoot = File(tmp, "private").apply { mkdirs() }
            fun chapter(root: File, title: String, name: String): File =
                File(root, "downloads/ExHentai (OTHER)/$title/$name").apply {
                    parentFile!!.mkdirs()
                    writeText("corrupted CBZ bytes")
                }
            val cbz = chapter(normal, "Gallery A", "Chapter.cbz")
            val privateCbz = chapter(privateRoot, "Gallery A", "Chapter.cbz")
            val unrelated = File(normal, "downloads/OTHER/Gallery B/Chapter.cbz").apply {
                parentFile!!.mkdirs()
                writeText("unrelated")
            }
            val scanner = ExhentaiPresenceScanner(setOf("ExHentai (OTHER)", "E-Hentai (EN)"))
            val first = scanner.scan(normal, privateRoot)
            check(first.availability == ExhentaiPresenceScanner.Availability.AVAILABLE)
            check(first.snapshot.candidatesFor("Gallery A") == listOf(cbz.canonicalPath))
            check(!first.snapshot.hasDownloaded("Gallery B"))
            check(privateCbz.isFile && unrelated.isFile)

            val second = chapter(normal, "Gallery A_1", "Other.cbz")
            val duplicate = scanner.scan(normal, privateRoot, first.snapshot)
            check(duplicate.snapshot.candidatesFor("Gallery A").size == 2)
            val renamed = File(cbz.parentFile, "Chapter_Baru.cbz")
            check(cbz.renameTo(renamed))
            val renamedResult = scanner.scan(normal, privateRoot, duplicate.snapshot)
            check(renamedResult.snapshot.candidatesFor("Gallery A").contains(renamed.canonicalPath))
            check(!renamedResult.snapshot.candidatesFor("Gallery A").contains(cbz.canonicalPath))
            check(second.delete())
            check(renamed.delete())
            val gone = scanner.scan(normal, privateRoot, renamedResult.snapshot)
            check(!gone.snapshot.hasDownloaded("Gallery A"))

            // Reproduce same-mtime additions (e.g. FAT timestamp granularity or a copied
            // folder preserving its original timestamp). Cached empty listings must not win.
            val galleryDir = cbz.parentFile!!
            val unchangedTimestamp = galleryDir.lastModified()
            val restored = chapter(normal, "Gallery A", "Chapter.cbz")
            check(galleryDir.setLastModified(unchangedTimestamp))
            val retained = scanner.scan(normal, privateRoot, gone.snapshot)
            check(retained.snapshot.hasDownloaded("Gallery A"))
            check(retained.snapshot.candidatesFor("Gallery A") == listOf(restored.canonicalPath))
            check(restored.delete())
            check(normal.renameTo(File(tmp, "unmounted")))
            val unavailable = scanner.scan(normal, privateRoot, retained.snapshot)
            check(unavailable.availability == ExhentaiPresenceScanner.Availability.UNAVAILABLE)
            check(unavailable.snapshot.hasDownloaded("Gallery A"))
            val switched = scanner.scan(privateRoot, normal, retained.snapshot)
            check(switched.snapshot.candidatesFor("Gallery A") == listOf(privateCbz.canonicalPath))

            val nested = File(tmp, "nested").apply { mkdirs() }
            val nestedCbz = chapter(nested, "Gallery C", "Chapter.cbz")
            check(nestedCbz.isFile)
            check(scanner.scan(nested, nested).snapshot.hasDownloaded("Gallery C"))
            check(!scanner.scan(nested, nestedCbz.parentFile).snapshot.hasDownloaded("Gallery C"))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
