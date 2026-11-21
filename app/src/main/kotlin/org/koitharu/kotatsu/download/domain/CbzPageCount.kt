package org.koitharu.kotatsu.download.domain

import java.io.File
import java.util.zip.ZipFile

/** Counts entries in an app-generated CBZ. The writer adds only pages, a cover and its index. */
internal object CbzPageCount {
	fun read(file: File, indexEntry: String, coverEntry: String?): Int =
		ZipFile(file).use { archive ->
			archive.entries().asSequence().count { !it.isDirectory && it.name != indexEntry && it.name != coverEntry }
		}
}
