package org.koitharu.kotatsu.download.ui.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prevent imported gallery identification from turning into an unverified completed download. */
class ExhentaiImportReuseBoundaryTest {
    @Test fun importedXmlDoesNotAuthorizeReuseAsACompletedDownload() {
        val source = sequenceOf(
            File("src/main/kotlin/org/koitharu/kotatsu/download/ui/worker/DownloadWorker.kt"),
            File("app/src/main/kotlin/org/koitharu/kotatsu/download/ui/worker/DownloadWorker.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("DownloadWorker source not found")
        val reuse = source.substringAfter("private suspend fun findCompletedExhentaiCopy(")
            .substringBefore("private suspend fun recordDownloadOwnership(")
            .replace(Regex("\\s+"), "")
        assertTrue(reuse.contains("if(match.verified)returnmatch.local"))
        assertTrue(reuse.contains("?.takeIf{it.verified}?.local"))
        assertFalse(reuse.contains("match.verified||match.local.file"))
    }
}
