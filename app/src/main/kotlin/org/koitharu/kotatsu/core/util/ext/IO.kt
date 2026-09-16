package org.koitharu.kotatsu.core.util.ext

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.source
import org.koitharu.kotatsu.core.util.CancellableSource
import org.koitharu.kotatsu.core.util.progress.ProgressResponseBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

fun ResponseBody.withProgress(progressState: MutableStateFlow<Float>): ResponseBody {
	return ProgressResponseBody(this, progressState)
}

suspend fun Source.cancellable(): Source {
	val job = currentCoroutineContext()[Job]
	return CancellableSource(job, this)
}

suspend fun BufferedSink.writeAllCancellable(source: Source) = withContext(Dispatchers.IO) {
	writeAll(source.cancellable())
}

/**
 * Cancellable copy variant for long-running transfers that also need a cooperative control point.
 * The callback runs before every read chunk, so callers such as DownloadWorker can react to Pause
 * without waiting for the current image/file to finish downloading.
 */
suspend fun BufferedSink.writeAllCancellable(
	source: Source,
	beforeRead: suspend () -> Unit,
) = withContext(Dispatchers.IO) {
	val cancellableSource = source.cancellable()
	val buffer = Buffer()
	while (true) {
		beforeRead()
		val read = cancellableSource.read(buffer, PAUSABLE_COPY_BUFFER_SIZE)
		if (read == -1L) break
		write(buffer, read)
	}
}

fun BufferedSource.readByteBuffer(): ByteBuffer {
	val bytes = readByteArray()
	return ByteBuffer.allocateDirect(bytes.size)
		.put(bytes)
		.rewind() as ByteBuffer
}

fun InputStream.toByteBuffer(): ByteBuffer {
	val outStream = ByteArrayOutputStream(available())
	copyTo(outStream)
	val bytes = outStream.toByteArray()
	return ByteBuffer.allocateDirect(bytes.size).put(bytes).position(0) as ByteBuffer
}

fun FileSystem.isDirectory(path: Path) = try {
	metadataOrNull(path)?.isDirectory == true
} catch (_: IOException) {
	false
}

fun FileSystem.isRegularFile(path: Path) = try {
	metadataOrNull(path)?.isRegularFile == true
} catch (_: IOException) {
	false
}

@CheckResult
fun ContentResolver.openSource(uri: Uri): Source = checkNotNull(openInputStream(uri)) {
	"Cannot open input stream from $uri"
}.source()

private const val PAUSABLE_COPY_BUFFER_SIZE = 64L * 1024L
