package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalDownloadScannerEhentaiTest {
    @Test fun knownBucketsStaySourceContainersEvenWhenTheFirstEightTitlesAreEmpty() = withRoot { root ->
        for (source in listOf("E-Hentai (EN)", "E-Hentai (ALL)", "ExHentai (OTHER)")) {
            val parent = File(root, "downloads/$source").apply { mkdirs() }
            repeat(10) { n -> File(parent, "Empty-$n").mkdirs() }
            val title = File(parent, "Valid gallery").apply { mkdirs() }
            File(title, "Chapter.cbz").createNewFile()
        }
        val found = LocalDownloadScanner.scan(listOf(root)).map { it.canonicalFile }.toSet()
        for (source in listOf("E-Hentai (EN)", "E-Hentai (ALL)", "ExHentai (OTHER)")) {
            val container = File(root, "downloads/$source")
            assertFalse("Source container was mistaken for a manga: $source", container.canonicalFile in found)
            assertTrue("Gallery was missed: $source", File(container, "Valid gallery").canonicalFile in found)
        }
    }

    @Test fun legacySourceLayoutWithoutDownloadsRootIsDetected() = withRoot { root ->
        val folder = File(root, "E-Hentai (EN)/Gallery").apply { mkdirs() }
        File(folder, "Chapter.cbz").createNewFile()
        assertTrue(folder.canonicalFile in LocalDownloadScanner.scan(listOf(root)).map { it.canonicalFile })
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ehentai-scanner").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
