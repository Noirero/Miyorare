package org.koitharu.kotatsu.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Real Android URI parsing, archive identity and Details chapter flags; no network or user files. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExhentaiImportedComicInfoTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var local: LocalMangaRepository

    private lateinit var root: File

    @Before fun setUp() {
        hiltRule.inject()
        root = File.createTempFile("ehentai-imported-", ".dir",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        check(root.delete() && root.mkdirs())
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun matchingInternalXmlLightsOnlyTheCorrectOnlineChapter() = runBlocking {
        val folder = File(root, "downloads/E-Hentai (EN)/Same Gallery").apply { mkdirs() }
        archive(folder, xml = xml(3863867L))
        val matched = local.findExhentaiInRoots(gallery(3863867L), listOf(root), true)
        assertNotNull(matched)
        assertTrue(detailsDownloaded(gallery(3863867L), matched))
        // XML is identity for reading, not an ownership receipt for deletion.
        val direct = ExhentaiDownloadedCopy.find(gallery(3863867L), folder, true)
        assertNotNull(direct)
        assertFalse(direct!!.verified)
        assertNull(local.findExhentaiInRoots(gallery(3863868L), listOf(root), true))
    }

    @Test fun xmlFreeCbzRemainsReadableLocallyWithoutInventingOnlineIdentity() = runBlocking {
        val folder = File(root, "downloads/E-Hentai (ALL)/Same Gallery").apply { mkdirs() }
        archive(folder)
        assertTrue(LocalMangaParser(folder).getManga(true).manga.chapters.orEmpty().isNotEmpty())
        assertNull(local.findExhentaiInRoots(gallery(3863867L), listOf(root), true))
    }

    @Test fun xmlFreeCbzOnlyMatchesWithExplicitExactOwnerHint() = runBlocking {
        val folder = File(root, "downloads/E-Hentai (EN)/Same Gallery").apply { mkdirs() }
        val cbz = archive(folder)
        assertNull(ExhentaiDownloadedCopy.find(gallery(10001L), cbz, true))
        val owned = ExhentaiDownloadedCopy.find(
            gallery(10001L), cbz, true, trustedOwnedPath = true,
        )
        assertNotNull(owned)
        assertFalse(owned!!.verified)
        assertFalse(detailsDownloaded(gallery(10002L), null))
    }

    @Test fun independentXmlEvidenceDisambiguatesENAndALL() = runBlocking {
        val en = File(root, "downloads/E-Hentai (EN)/Same Gallery").apply { mkdirs() }
        val all = File(root, "downloads/E-Hentai (ALL)/Same Gallery").apply { mkdirs() }
        archive(en, xml = xml(10001))
        archive(all, name = "Imported gallery.cbz", xml = xml(10002))
        assertEquals(File(en, "Chapter.cbz").canonicalFile,
            local.findExhentaiInRoots(gallery(10001), listOf(root), true)?.file?.canonicalFile)
        assertEquals(File(all, "Imported gallery.cbz").canonicalFile,
            local.findExhentaiInRoots(gallery(10002), listOf(root), true)?.file?.canonicalFile)
        assertNull(local.findExhentaiInRoots(gallery(10003), listOf(root), true))
    }

    @Test fun duplicateIdentityAcrossENAndALLIsAmbiguous() = runBlocking {
        for (lang in listOf("EN", "ALL")) {
            val folder = File(root, "downloads/E-Hentai ($lang)/Same Gallery").apply { mkdirs() }
            archive(folder, xml = xml(10001))
        }
        assertNull(local.findExhentaiInRoots(gallery(10001), listOf(root), true))
    }

    @Test fun adjacentSidecarOnlyWorksForOneCbzAndRejectsWrongGallery() = runBlocking {
        val folder = File(root, "downloads/E-Hentai (EN)/Same Gallery").apply { mkdirs() }
        archive(folder)
        File(folder, "ComicInfo.xml").writeText(xml(10001))
        assertNotNull(local.findExhentaiInRoots(gallery(10001), listOf(root), true))
        assertNull(local.findExhentaiInRoots(gallery(10002), listOf(root), true))
        archive(folder, name = "Chapter_abcdef.cbz")
        assertNull(local.findExhentaiInRoots(gallery(10001), listOf(root), true))
    }

    private fun detailsDownloaded(gallery: Manga, saved: org.koitharu.kotatsu.local.domain.model.LocalManga?): Boolean =
        MangaDetails(gallery).copy(localManga = saved)
            .mapChapters(0L, 0, null, emptyList(), false, false).single().isDownloaded

    private fun gallery(id: Long): Manga {
        val source = MangaSource(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
        val url = "https://exhentai.org/g/$id/token/"
        return SampleData.mangaDetails.copy(
            id = id, title = "Same Gallery", source = source, url = url, publicUrl = url,
            chapters = listOf(SampleData.chapter.copy(
                id = id + 12345L, title = "Chapter", number = 1f, volume = 0,
                source = source, url = url, branch = null, scanlator = null,
            )),
        )
    }

    private fun xml(id: Long) =
        "<?xml version=\"1.0\"?><ComicInfo><Series>Same Gallery</Series><Web>https://e-hentai.org/g/$id/token/</Web></ComicInfo>"

    private fun archive(folder: File, name: String = "Chapter.cbz", xml: String? = null): File =
        File(folder, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("1.webp"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                if (xml != null) {
                    zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zip.write(xml.toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
