package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalReaderCacheRegressionTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/kotlin/org/koitharu/kotatsu", path),
        File("app/src/main/kotlin/org/koitharu/kotatsu", path),
    ).first { it.isFile }.readText()

    @Test fun localArchivePagesAvoidSecondDiskCopy() {
        val loader = source("reader/domain/PageLoader.kt")
        assertTrue(loader.contains("uri.isZipUri() && page.source == LocalMangaSource"))
        assertTrue(loader.contains("uri.isZipUri() -> materializeCbzPage("))
        assertTrue(loader.contains("page.source != LocalMangaSource"))
    }

    @Test fun temporaryMetadataArchivesAreReleased() {
        val reader = source("local/library/LocalContentReader.kt")
        val metadata = reader.substringAfter("internal suspend fun metadata(").substringBefore("suspend fun epubHtml(")
        assertTrue(metadata.contains("withContext(NonCancellable) { releaseMaterialized(file) }"))
    }

    @Test fun pdfRenderCacheHasBudgetAndProtectsRecentPages() {
        val pdf = source("local/data/input/LocalPdfCache.kt")
        assertTrue(pdf.contains("PDF_CACHE_BUDGET_BYTES = 64L * 1024 * 1024"))
        assertTrue(pdf.contains("pruneRenderedPages(outputFile)"))
        assertTrue(pdf.contains("page == current || page.lastModified() >= cutoff"))
    }
}
