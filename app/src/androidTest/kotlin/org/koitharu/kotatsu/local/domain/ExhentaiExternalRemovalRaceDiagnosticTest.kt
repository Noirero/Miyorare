package org.koitharu.kotatsu.local.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Original T01. Report the actual result separately under owner-approved Q27-Q28 R1. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExhentaiExternalRemovalRaceDiagnosticTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var deletion: DeleteLocalMangaUseCase

	private lateinit var root: File
	private lateinit var prefs: SharedPreferences
	private lateinit var previousPrefs: Map<String, *>

	@Before fun setUp() {
		hilt.inject()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		root = File(context.cacheDir, "exhentai-title-${System.nanoTime()}").canonicalFile
		check(root.mkdirs())
		check(File(root, "private").mkdirs())
		prefs = PreferenceManager.getDefaultSharedPreferences(context)
		previousPrefs = prefs.all.toMap()
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, File(root, "private").path)
			remove("normal_download_legacy_roots")
			remove("private_download_legacy_roots")
		}
	}

	@After fun tearDown() {
		if (::previousPrefs.isInitialized) {
			prefs.edit(commit = true) {
				clear()
				for ((key, value) in previousPrefs) when (value) {
					is String -> putString(key, value)
					is Int -> putInt(key, value)
					is Long -> putLong(key, value)
					is Boolean -> putBoolean(key, value)
					is Float -> putFloat(key, value)
					is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
				}
			}
		}
		if (::root.isInitialized) root.deleteRecursively()
	}

	@Test fun confirmedDeleteRejectsReplacementAtRemovalBoundary() = runBlocking {
		val chapter = archive("Legacy Gallery", "Chapter.cbz")
		val manga = gallery(990087, GEKKO_SOURCE)
		val space = FavouriteSpace.NORMAL
		val approval = requireNotNull(deletion.previewExhentaiPhysicalDeletion(manga, space, chapter))
		val original = File(chapter.parentFile, "Original.cbz")
		val bytes = chapter.readBytes()
		val replacement = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
		var reached = false
		val result = runCatching {
			deletion.deleteConfirmedExhentaiPhysicalFile(manga, space, approval) {
				// Interleave a real rename and same-size/same-mtime replacement after the last check.
				check(chapter.renameTo(original))
				chapter.writeBytes(replacement)
				check(chapter.setLastModified(approval.lastModified))
				reached = true
			}
		}
		assertTrue("Removal boundary was not reached", reached)
		assertArrayEquals(bytes, original.readBytes())
		assertTrue("An unapproved replacement must not be removed", chapter.isFile)
		assertArrayEquals(replacement, chapter.readBytes())
		assertTrue("Changed target must be rejected", result.exceptionOrNull() is UnverifiedDownloadRemovalException)
	}

	private fun gallery(id: Long, sourceName: String): Manga {
		val source = MangaSource(sourceName)
		val url = "https://exhentai.org/g/$id/token/"
		return SampleData.mangaDetails.copy(
			id = id, title = "Legacy Gallery", source = source,
			url = url, publicUrl = url,
			chapters = listOf(SampleData.chapter.copy(
				id = id + 800000, title = "Chapter", number = 1f, volume = 0,
				source = source, url = url, branch = null, scanlator = null,
			)),
		)
	}

	private fun archive(
		folder: String, name: String, comment: String? = null, private: Boolean = false,
		index: String? = null, comicInfo: String? = null, destination: File? = null,
		pageBytes: ByteArray = byteArrayOf(1, 2, 3),
	): File {
		val base = destination ?: if (private) File(root, "private") else root
		val dir = File(base, "downloads/ExHentai (OTHER)/$folder")
		check(dir.mkdirs())
		return File(dir, name).apply {
			ZipOutputStream(outputStream()).use { zip ->
				if (comment != null) zip.setComment(comment)
				zip.putNextEntry(ZipEntry("1.webp"))
				zip.write(pageBytes)
				zip.closeEntry()
				if (index != null) {
					zip.putNextEntry(ZipEntry("index.json"))
					zip.write(index.toByteArray())
					zip.closeEntry()
				}
				if (comicInfo != null) {
					zip.putNextEntry(ZipEntry("ComicInfo.xml"))
					zip.write(comicInfo.toByteArray())
					zip.closeEntry()
				}
			}
		}
	}

	companion object {
		private const val GEKKO_SOURCE = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
	}
}
