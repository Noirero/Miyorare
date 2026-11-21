package org.koitharu.kotatsu.core.logs

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Holds a bounded checkpoint and a separately exportable completed recording. */
internal class VerboseLogStore(private val directory: File) {
	private val active = File(directory, "active.txt")
	private val previous = File(directory, "previous.txt")
	private val saved = File(directory, "last.txt")

	val hasSaved: Boolean get() = saved.isFile
	val hasInterrupted: Boolean get() = active.isFile || previous.isFile

	fun prepare(resume: Boolean) {
		if (resume && active.isFile) {
			replace(active, previous)
		} else if (!resume) {
			delete(active)
			delete(previous)
		}
	}

	fun checkpoint(report: String) = write(active, report)

	fun finish(report: String, partial: Boolean): Boolean {
		val incomplete = partial || previous.isFile
		write(saved, buildString {
			appendLine(if (incomplete) "capture_status=partial" else "capture_status=complete")
			if (previous.isFile) {
				appendLine("--- Previous process checkpoint (partial) ---")
				appendLine(previous.readText())
				appendLine("--- Final process segment ---")
			}
			append(report)
		})
		delete(active)
		delete(previous)
		return incomplete
	}

	fun export(recovered: String? = null, open: () -> OutputStream?): Boolean {
		if (recovered == null && !saved.isFile) return false
		return try {
			val destination = open() ?: return false
			destination.use { output ->
				if (recovered == null) saved.inputStream().use { it.copyTo(output) }
				else output.write(("capture_status=partial\n" + recovered).toByteArray())
			}
			true
		} catch (_: IOException) {
			false
		} catch (_: SecurityException) {
			false
		}
	}

	fun deleteSaved() = delete(saved)

	fun savedIsPartial(): Boolean = saved.bufferedReader().use { it.readLine() == "capture_status=partial" }

	private fun write(target: File, content: String) {
		directory.mkdirs()
		val temp = File(directory, "${target.name}.tmp")
		try {
			temp.writeText(content)
			replace(temp, target)
		} finally {
			temp.delete()
		}
	}

	private fun replace(source: File, target: File) {
		Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	}

	private fun delete(file: File) {
		Files.deleteIfExists(file.toPath())
	}
}
