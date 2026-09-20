package org.koitharu.kotatsu.backup.local.domain

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Turns a locally stored custom cover into portable base64 data and back. Cover payloads are bounded
 * so a corrupt/accidental giant image cannot dominate backup/restore memory.
 */
@Reusable
class CustomCoverCodec @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	class EncodedCover(val data: String, val extension: String?)

	suspend fun read(url: String?): EncodedCover? {
		val uri = url?.toUriOrNull() ?: return null
		if (!uri.isFileUri() && uri.scheme != "content") {
			return null
		}
		return withContext(Dispatchers.IO) {
			runCatching {
				val bytes = if (uri.isFileUri()) {
					val file = uri.toFileOrNull()?.takeIf(File::isFile) ?: return@runCatching null
					if (file.length() > MAX_COVER_BYTES) {
						throw IOException("Custom cover exceeds ${MAX_COVER_BYTES / (1024 * 1024)} MiB")
					}
					file.inputStream().buffered().use { it.readBytesLimited(MAX_COVER_BYTES) }
				} else {
					context.contentResolver.openInputStream(uri)?.buffered()?.use {
						it.readBytesLimited(MAX_COVER_BYTES)
					}
				} ?: return@runCatching null
				val extension = uri.lastPathSegment
					?.substringAfterLast('.', "")
					?.takeIf { it.isSafeFileExtension() }
					?: context.contentResolver.getType(uri)
						?.toMimeTypeOrNull()
						?.let(MimeTypes::getExtension)
				EncodedCover(
					data = Base64.encodeToString(bytes, Base64.NO_WRAP),
					extension = extension,
				)
			}.onFailure {
				Log.w(TAG, "failed to read custom cover '$url'", it)
			}.getOrNull()
		}
	}

	suspend fun materialize(
		mangaId: Long,
		coverData: String,
		coverFileExtension: String?,
		previousUrl: String?,
	): String? = withContext(Dispatchers.IO) {
		runCatching {
			require(coverData.length <= MAX_BASE64_CHARS) { "Custom cover payload is too large" }
			val bytes = Base64.decode(coverData, Base64.DEFAULT)
			require(bytes.size <= MAX_COVER_BYTES) { "Decoded custom cover payload is too large" }
			val directory = context.getExternalFilesDir(COVERS_DIR) ?: return@runCatching null
			if (!directory.exists() && !directory.mkdirs()) {
				return@runCatching null
			}
			val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
			val digestName = digest
				.take(12)
				.joinToString("") { "%02x".format(it) }
			val extension = coverFileExtension
				?.takeIf { it.isSafeFileExtension() }
				?.let { ".$it" }
				.orEmpty()
			val destination = File(directory, "$SYNCED_COVER_PREFIX${mangaId}_$digestName$extension")
			val matches = destination.isFile &&
				destination.length() == bytes.size.toLong() &&
				destination.inputStream().buffered().use { stream ->
					stream.sha256().contentEquals(digest)
				}
			if (!matches) {
				destination.outputStream().buffered().use { it.write(bytes) }
			}
			deleteReplacedSyncedCover(previousUrl, destination)
			destination.toUri().toString()
		}.onFailure {
			Log.w(TAG, "failed to restore custom cover for manga $mangaId", it)
		}.getOrNull()
	}

	fun isPortableCoverUrl(url: String?): Boolean {
		val uri = url?.toUriOrNull() ?: return true
		return !uri.isFileUri() && uri.scheme != "content"
	}

	private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
		val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var total = 0
		while (true) {
			val read = read(buffer)
			if (read < 0) break
			if (total + read > maxBytes) throw IOException("Custom cover exceeds $maxBytes bytes")
			output.write(buffer, 0, read)
			total += read
		}
		return output.toByteArray()
	}

	private fun InputStream.sha256(): ByteArray {
		val digest = MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		while (true) {
			val read = read(buffer)
			if (read < 0) break
			digest.update(buffer, 0, read)
		}
		return digest.digest()
	}

	private fun deleteReplacedSyncedCover(previousUrl: String?, replacement: File) {
		val previous = previousUrl?.toUriOrNull()?.toFileOrNull() ?: return
		val coverDirectory = replacement.parentFile?.canonicalFile ?: return
		val oldFile = runCatching { previous.canonicalFile }.getOrNull() ?: return
		if (oldFile != replacement.canonicalFile &&
			oldFile.parentFile == coverDirectory &&
			oldFile.name.startsWith(SYNCED_COVER_PREFIX)
		) {
			oldFile.delete()
		}
	}

	private fun String.isSafeFileExtension(): Boolean =
		length in 1..10 && all { it.isLetterOrDigit() }

	private companion object {
		const val TAG = "CustomCoverCodec"
		const val COVERS_DIR = "covers"
		const val SYNCED_COVER_PREFIX = "sync_"
		const val MAX_COVER_BYTES = 8 * 1024 * 1024
		const val MAX_BASE64_CHARS = (MAX_COVER_BYTES * 4 / 3) + 16
	}
}
