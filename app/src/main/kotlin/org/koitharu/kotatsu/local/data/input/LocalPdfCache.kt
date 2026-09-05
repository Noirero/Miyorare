package org.koitharu.kotatsu.local.data.input

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.internal.platform.PlatformRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Renders local PDF pages into the app cache without modifying the source PDF.
 * Cache identity includes file path, size and modified time so replaced PDFs get fresh pages.
 */
object LocalPdfCache {

	private const val CACHE_DIR_NAME = "local_pdf_pages"
	private const val SOURCE_FILE_NAME = ".source"
	private const val COVER_FILE_NAME = "cover.png"
	private const val COVER_MAX_RENDER_DIMENSION = 768
	// Rendering every page at 4x/4096px made long local PDFs spend tens of seconds in PNG deflate,
	// increased GC pressure and could contribute to foreground ANRs while the library UI was active.
	// 2560px is still comfortably above typical phone display resolution while cutting bitmap area,
	// memory use and PNG compression work substantially.
	private const val PDF_RENDER_SCALE = 2.5f
	private const val MAX_RENDER_DIMENSION = 2560
	private val coverRenderingSuppressed = ThreadLocal<Boolean>()

	/**
	 * Local index scans only need metadata. Keep an already rendered cover if one exists, but do not
	 * open [PdfRenderer] or create a new bitmap while a broad filesystem scan is in progress.
	 */
	suspend fun <T> withoutCoverRendering(block: suspend () -> T): T =
		withContext(coverRenderingSuppressed.asContextElement(true)) { block() }

	@Synchronized
	fun renderCover(pdf: File): File? = runCatching {
		val outputDir = cacheDirFor(pdf)
		File(outputDir, COVER_FILE_NAME).takeIf { it.isUsableCacheFile() }?.let {
			return@runCatching it
		}
		// Reuse a full-resolution first page left by older versions/reader sessions instead of
		// opening PdfRenderer again just to produce another cover for the same unchanged PDF.
		File(outputDir, pageFileName(0)).takeIf { it.isUsableCacheFile() }?.let {
			return@runCatching it
		}
		if (coverRenderingSuppressed.get() == true) {
			return@runCatching null
		}
		openRenderer(pdf) { renderer ->
			if (renderer.pageCount <= 0) {
				return@openRenderer null
			}
			renderPage(
				renderer = renderer,
				pageIndex = 0,
				outputDir = outputDir,
				outputFileName = COVER_FILE_NAME,
				maxRenderDimension = COVER_MAX_RENDER_DIMENSION,
			)
		}
	}.getOrNull()

	/**
	 * Return stable cache targets for all pages without rendering them eagerly. The tiny source marker
	 * lets [materializePage] render only the page requested by the reader.
	 */
	@Synchronized
	fun renderPages(pdf: File): List<File> {
		return openRenderer(pdf) { renderer ->
			if (renderer.pageCount <= 0) {
				throw IOException("PDF has no pages: $pdf")
			}
			val outputDir = cacheDirFor(pdf)
			ensureOutputDir(outputDir)
			File(outputDir, SOURCE_FILE_NAME).writeText(pdf.absolutePath)
			List(renderer.pageCount) { index -> File(outputDir, pageFileName(index)) }
		}
	}

	fun isPdfPage(file: File): Boolean {
		return file.name.startsWith("page_") &&
			file.name.endsWith(".png") &&
			file.parentFile?.parentFile?.name == CACHE_DIR_NAME
	}

	/** Render one lazy PDF page target produced by [renderPages]. */
	@Synchronized
	fun materializePage(file: File): File {
		if (!isPdfPage(file)) {
			throw IOException("Not a local PDF cache page: $file")
		}
		val outputDir = file.parentFile ?: throw IOException("PDF cache page has no parent: $file")
		val sourceMarker = File(outputDir, SOURCE_FILE_NAME)
		if (!sourceMarker.isFile) {
			throw IOException("PDF cache source is missing: $file")
		}
		val pdf = File(sourceMarker.readText())
		validateSourceIdentity(pdf, outputDir)
		if (file.isUsableCacheFile()) {
			return file
		}
		val pageIndex = file.name
			.removePrefix("page_")
			.removeSuffix(".png")
			.toIntOrNull()
			?.minus(1)
			?: throw IOException("Invalid PDF cache page name: ${file.name}")
		return openRenderer(pdf) { renderer ->
			validateSourceIdentity(pdf, outputDir)
			if (pageIndex !in 0 until renderer.pageCount) {
				throw IOException("PDF page is out of range: $pageIndex for $pdf")
			}
			val result = renderPage(renderer, pageIndex, outputDir)
			validateSourceIdentity(pdf, outputDir)
			result
		}
	}

	private inline fun <T> openRenderer(pdf: File, block: (PdfRenderer) -> T): T {
		if (!pdf.isFile || !pdf.canRead()) {
			throw IOException("Cannot read PDF: $pdf")
		}
		return ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
			PdfRenderer(descriptor).use(block)
		}
	}

	private fun renderPage(
		renderer: PdfRenderer,
		pageIndex: Int,
		outputDir: File,
		outputFileName: String = pageFileName(pageIndex),
		maxRenderDimension: Int = MAX_RENDER_DIMENSION,
	): File {
		val outputFile = File(outputDir, outputFileName)
		if (outputFile.isUsableCacheFile()) {
			return outputFile
		}
		ensureOutputDir(outputDir)

		val tempFile = File(outputDir, outputFile.name + ".tmp")
		renderer.openPage(pageIndex).use { page ->
			val maxPageSize = maxOf(page.width, page.height).coerceAtLeast(1)
			val scale = minOf(PDF_RENDER_SCALE, maxRenderDimension / maxPageSize.toFloat())
			val matrix = Matrix().apply { setScale(scale, scale) }
			val width = (page.width * scale).roundToInt().coerceAtLeast(1)
			val height = (page.height * scale).roundToInt().coerceAtLeast(1)
			val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
			try {
				bitmap.eraseColor(Color.WHITE)
				page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
				tempFile.outputStream().buffered().use { output ->
					if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
						throw IOException("Cannot encode rendered PDF page $pageIndex")
					}
				}
			} finally {
				bitmap.recycle()
			}
		}

		if (outputFile.exists() && !outputFile.delete()) {
			tempFile.delete()
			throw IOException("Cannot replace PDF cache page: $outputFile")
		}
		if (!tempFile.renameTo(outputFile)) {
			tempFile.copyTo(outputFile, overwrite = true)
			tempFile.delete()
		}
		return outputFile
	}

	private fun validateSourceIdentity(pdf: File, outputDir: File) {
		val expectedDir = cacheDirFor(pdf)
		val expectedPath = runCatching { expectedDir.canonicalPath }.getOrDefault(expectedDir.absolutePath)
		val actualPath = runCatching { outputDir.canonicalPath }.getOrDefault(outputDir.absolutePath)
		if (actualPath != expectedPath) {
			throw IOException("PDF source changed while page cache was active: $pdf")
		}
	}

	private fun ensureOutputDir(outputDir: File) {
		if (!outputDir.exists() && !outputDir.mkdirs()) {
			throw IOException("Cannot create PDF cache directory: $outputDir")
		}
	}

	private fun pageFileName(pageIndex: Int): String =
		"page_${(pageIndex + 1).toString().padStart(5, '0')}.png"

	private fun File.isUsableCacheFile(): Boolean = isFile && length() > 0L

	private fun cacheDirFor(pdf: File): File {
		val context = checkNotNull(PlatformRegistry.applicationContext) {
			"Application context is not initialized"
		}
		return File(File(context.cacheDir, CACHE_DIR_NAME), cacheKey(pdf))
	}

	private fun cacheKey(pdf: File): String {
		val path = runCatching { pdf.canonicalPath }.getOrDefault(pdf.absolutePath)
		val identity = "$path\u0000${pdf.length()}\u0000${pdf.lastModified()}"
		val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
		return buildString(digest.size * 2) {
			for (byte in digest) {
				append(((byte.toInt() ushr 4) and 0xF).toString(16))
				append((byte.toInt() and 0xF).toString(16))
			}
		}
	}
}
