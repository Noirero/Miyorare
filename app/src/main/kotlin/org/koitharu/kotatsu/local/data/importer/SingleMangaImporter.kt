package org.koitharu.kotatsu.local.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.local.data.hasEpubExtension
import org.koitharu.kotatsu.local.data.hasPdfExtension
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.isSupportedArchive
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import javax.inject.Inject

data class EpubImportPreview(
	val fileName: String,
	val title: String,
	val authors: Set<String>,
	val description: String?,
	val sectionCount: Int,
	val cover: Bitmap?,
	val existingManga: Manga?,
)

@Reusable
class SingleMangaImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	private val contentResolver = context.contentResolver

	suspend fun import(uri: Uri): LocalManga {
		val result = if (isDirectory(uri)) {
			importDirectory(uri)
		} else {
			importFile(uri)
		}
		localStorageChanges.emit(result)
		return result
	}

	/**
	 * Reads enough of an external EPUB to confirm that it is a real book and to populate the
	 * lightweight import confirmation UI. The source is copied to cache because [EpubParser] works
	 * with seekable files, while Android document providers expose a content Uri/stream.
	 */
	suspend fun previewEpub(uri: Uri): EpubImportPreview = withContext(Dispatchers.IO) {
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!hasEpubExtension(name)) {
			throw UnsupportedFileException("Unsupported EPUB $name on $uri")
		}
		val previewFile = File.createTempFile("epub-preview-", ".epub", context.cacheDir)
		try {
			copyUriToFile(uri, previewFile)
			val book = EpubParser.parse(previewFile)
			if (book.spine.isEmpty()) {
				throw UnsupportedFileException("EPUB has no readable sections: $name")
			}
			val existingManga = File(getOutputDir(), name)
				.takeIf { it.isFile }
				?.let { existing ->
					runCatching { LocalMangaParser(existing).getManga(withDetails = false).manga }.getOrNull()
				}
			EpubImportPreview(
				fileName = name,
				title = book.title?.takeIf { it.isNotBlank() }
					?: name.substringBeforeLast('.', name).replace('_', ' '),
				authors = book.authors,
				description = book.description,
				sectionCount = book.spine.size,
				cover = readEpubCover(previewFile, book.coverHref),
				existingManga = existingManga,
			)
		} finally {
			previewFile.delete()
		}
	}

	private suspend fun importFile(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!isSupportedArchive(name)) {
			throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		val dest = when {
			hasPdfExtension(name) -> importPdfAsCbz(uri, name)
			hasEpubExtension(name) -> importEpubAtomically(uri, name)
			else -> File(getOutputDir(), name).also { outputFile ->
				if (outputFile.isDirectory && outputFile.listFiles()?.isEmpty() == true && !outputFile.delete()) {
					throw IOException("Cannot remove empty import directory: $outputFile")
				}
				copyUriToFile(uri, outputFile)
			}
		}
		LocalMangaParser(dest).getManga(withDetails = false)
	}

	/**
	 * External EPUBs are never written straight to their final path. A complete temporary copy is
	 * parsed first, then swapped into place. This keeps a truncated provider read or malformed EPUB
	 * from leaving a half-written book (or destroying a previously imported copy).
	 */
	private suspend fun importEpubAtomically(uri: Uri, sourceName: String): File {
		val outputDir = getOutputDir()
		val outputFile = File(outputDir, sourceName)
		if (outputFile.isDirectory && outputFile.listFiles()?.isEmpty() == true && !outputFile.delete()) {
			throw IOException("Cannot remove empty import directory: $outputFile")
		}
		if (outputFile.isDirectory) {
			throw IOException("Import destination is a directory: $outputFile")
		}

		val tempFile = File.createTempFile(".${outputFile.name}.", ".importing", outputDir)
		var backupFile: File? = null
		try {
			copyUriToFile(uri, tempFile)
			val book = EpubParser.parse(tempFile)
			if (book.spine.isEmpty()) {
				throw UnsupportedFileException("EPUB has no readable sections: $sourceName")
			}

			if (outputFile.exists()) {
				backupFile = File.createTempFile(".${outputFile.name}.", ".backup", outputDir)
				if (!backupFile.delete()) {
					throw IOException("Cannot prepare EPUB backup: $backupFile")
				}
				if (!outputFile.renameTo(backupFile)) {
					throw IOException("Cannot preserve existing EPUB before import: $outputFile")
				}
			}
			if (!tempFile.renameTo(outputFile)) {
				backupFile?.takeIf { it.exists() }?.renameTo(outputFile)
				throw IOException("Cannot finalize EPUB import: $outputFile")
			}
			backupFile?.delete()
			return outputFile
		} catch (e: Exception) {
			tempFile.delete()
			if (!outputFile.exists()) {
				backupFile?.takeIf { it.exists() }?.renameTo(outputFile)
			}
			throw e
		} finally {
			tempFile.delete()
			if (outputFile.exists()) {
				backupFile?.takeIf { it.exists() }?.delete()
			}
		}
	}

	private suspend fun copyUriToFile(uri: Uri, outputFile: File) {
		runInterruptible {
			contentResolver.openSource(uri)
		}.use { source ->
			outputFile.sink().buffer().use { output ->
				output.writeAllCancellable(source)
			}
		}
	}

	private fun readEpubCover(file: File, coverHref: String?): Bitmap? {
		if (coverHref.isNullOrBlank()) return null
		return runCatching {
			ZipFile(file).use { zip ->
				val entry = zip.getEntry(coverHref) ?: zip.getEntry(coverHref.removePrefix("/")) ?: return@use null
				val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
				zip.getInputStream(entry).use { input ->
					BitmapFactory.decodeStream(input, null, bounds)
				}
				if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
				var sampleSize = 1
				while (
					maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
					MAX_EPUB_PREVIEW_COVER_DIMENSION
				) {
					sampleSize *= 2
				}
				val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
				zip.getInputStream(entry).use { input ->
					BitmapFactory.decodeStream(input, null, options)
				}
			}
		}.getOrNull()
	}

	private suspend fun importPdfAsCbz(uri: Uri, sourceName: String): File {
		val outputName = sourceName.substringBeforeLast('.', sourceName) + ".cbz"
		val outputFile = File(getOutputDir(), outputName)
		try {
			contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
				renderPdfToCbz(pfd, outputFile)
			} ?: throw IOException("Cannot open descriptor for uri: $uri")
			return outputFile
		} catch (e: Exception) {
			outputFile.delete()
			throw e
		}
	}

	private fun renderPdfToCbz(pfd: ParcelFileDescriptor, outputFile: File) {
		PdfRenderer(pfd).use { renderer ->
			if (renderer.pageCount <= 0) {
				throw IOException("PDF has no pages")
			}
			ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
				for (index in 0 until renderer.pageCount) {
					renderer.openPage(index).use { page ->
						val maxPageSize = maxOf(page.width, page.height).coerceAtLeast(1)
						val scale = minOf(PDF_RENDER_SCALE, MAX_RENDER_DIMENSION / maxPageSize.toFloat())
						val matrix = Matrix().apply { setScale(scale, scale) }
						val width = (page.width * scale).roundToInt().coerceAtLeast(1)
						val height = (page.height * scale).roundToInt().coerceAtLeast(1)
						val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
						bitmap.eraseColor(Color.WHITE)
						page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
						val entryName = String.format(Locale.US, "%04d.png", index + 1)
						zip.putNextEntry(ZipEntry(entryName))
						bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
						zip.closeEntry()
						bitmap.recycle()
					}
				}
			}
		}
	}

	private suspend fun importDirectory(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		val allFiles = root.listFiles()
		val pdfFiles = allFiles
			.filter { it.isFile && hasPdfExtension(it.name ?: "") }
			.sortedBy { it.name }

		if (pdfFiles.isNotEmpty()) {
			val dest = File(getOutputDir(), root.requireName())
			dest.mkdir()
			for (pdfFile in pdfFiles) {
				val chapterName = pdfFile.name!!.substringBeforeLast('.')
				val cbzFile = File(dest, "$chapterName.cbz")
				try {
					contentResolver.openFileDescriptor(pdfFile.uri, "r")?.use { pfd ->
						renderPdfToCbz(pfd, cbzFile)
					} ?: throw IOException("Cannot open PDF: ${pdfFile.name}")
				} catch (e: Exception) {
					cbzFile.delete()
					throw e
				}
			}
			return@withContext LocalMangaParser(dest).getManga(withDetails = false)
		}

		val dest = File(getOutputDir(), root.requireName())
		dest.mkdir()
		for (docFile in allFiles) {
			docFile.copyTo(dest)
		}
		LocalMangaParser(dest).getManga(withDetails = false)
	}

	private suspend fun DocumentFile.copyTo(destDir: File) {
		if (isDirectory) {
			val subDir = File(destDir, requireName())
			subDir.mkdir()
			for (docFile in listFiles()) {
				docFile.copyTo(subDir)
			}
		} else {
			source().use { input ->
				File(destDir, requireName()).sink().buffer().use { output ->
					output.writeAllCancellable(input)
				}
			}
		}
	}

	private suspend fun getOutputDir(): File {
		return storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)?.isDirectory == true
		}.getOrDefault(false)
	}

	private companion object {
		private const val PDF_RENDER_SCALE = 4f
		private const val MAX_RENDER_DIMENSION = 4096
		private const val MAX_EPUB_PREVIEW_COVER_DIMENSION = 512
	}
}
