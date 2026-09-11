package org.koitharu.kotatsu.download.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * Cheap integrity check for downloaded manga pages.
 *
 * It intentionally avoids decoding the full bitmap: a Doctor pass must not create a memory spike
 * after every download. Common formats get an inexpensive container/signature-tail check, while
 * other formats fall back to Miyorare's existing MIME probe.
 */
object DownloadDoctor {

	suspend fun isHealthyPage(file: File): Boolean = runInterruptible(Dispatchers.IO) {
		if (!file.isFile || file.length() < MIN_PAGE_BYTES) return@runInterruptible false
		val mime = BitmapDecoderCompat.probeMimeType(file) ?: return@runInterruptible false
		when (mime.subtype.lowercase()) {
			"jpeg", "jpg" -> tailContains(file, JPEG_END)
			"png" -> tailContains(file, PNG_END)
			"gif" -> tailContains(file, GIF_END)
			"webp" -> hasWebpContainer(file)
			// AVIF is already identified by the app's AVIF-aware probe. Full decode here would defeat
			// the low-memory purpose of Doctor, so keep this check intentionally conservative.
			"avif" -> file.length() >= 24L
			else -> true
		}
	}

	/**
	 * A few servers/proxies append harmless bytes after the formal image terminator. Restrict the
	 * tolerance to a tiny tail window so truncated images are still rejected without rejecting such
	 * otherwise-readable files.
	 */
	private fun tailContains(file: File, marker: ByteArray): Boolean = RandomAccessFile(file, "r").use { input ->
		val length = input.length()
		if (length < marker.size) return@use false
		val bytesToRead = minOf(length, TAIL_SCAN_BYTES.toLong()).toInt()
		input.seek(length - bytesToRead)
		val tail = ByteArray(bytesToRead)
		input.readFully(tail)
		containsSequence(tail, marker)
	}

	private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
		if (needle.isEmpty() || haystack.size < needle.size) return false
		for (start in 0..haystack.size - needle.size) {
			var matches = true
			for (offset in needle.indices) {
				if (haystack[start + offset] != needle[offset]) {
					matches = false
					break
				}
			}
			if (matches) return true
		}
		return false
	}

	private fun hasWebpContainer(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
		if (input.length() < 16L) return@use false
		val header = ByteArray(12)
		input.readFully(header)
		if (!header.copyOfRange(0, 4).contentEquals(RIFF) || !header.copyOfRange(8, 12).contentEquals(WEBP)) {
			return@use false
		}
		val declaredSize = (header[4].toLong() and 0xffL) or
			((header[5].toLong() and 0xffL) shl 8) or
			((header[6].toLong() and 0xffL) shl 16) or
			((header[7].toLong() and 0xffL) shl 24)
		declaredSize + 8L <= input.length()
	}

	private const val MIN_PAGE_BYTES = 16L
	private const val TAIL_SCAN_BYTES = 64
	private val RIFF = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
	private val WEBP = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
	private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
	private val GIF_END = byteArrayOf(0x3B)
	private val PNG_END = byteArrayOf(
		0x00, 0x00, 0x00, 0x00,
		0x49, 0x45, 0x4E, 0x44,
		0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
	)
}
