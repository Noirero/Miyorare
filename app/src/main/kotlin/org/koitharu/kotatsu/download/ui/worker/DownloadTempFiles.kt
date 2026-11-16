package org.koitharu.kotatsu.download.ui.worker

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Tracks only transfer scratch files created by one worker, never archive staging or resume files. */
internal class DownloadTempFiles : Closeable {

	private val files = ArrayList<File>()

	@Synchronized
	fun create(destination: File, extension: String?): File = File(
		destination,
		buildString {
			append(UUID.randomUUID())
			if (!extension.isNullOrEmpty()) {
				append('.')
				append(extension)
			}
			append(".tmp")
		},
	).also(files::add)

	@Synchronized
	override fun close() {
		for (file in files) file.delete()
	}
}
