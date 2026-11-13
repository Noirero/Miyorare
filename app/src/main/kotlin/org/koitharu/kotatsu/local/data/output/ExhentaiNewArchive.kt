package org.koitharu.kotatsu.local.data.output

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Task-owned staging and create-only publication, serialized across galleries and formats. */
internal class ExhentaiNewArchive private constructor(
	val target: File,
	val staging: File,
	private val createdDirectory: File?,
) : Closeable {

	var isCommitted = false
		private set

	fun commit() = synchronized(lock) {
		check(this in active && !isCommitted) { "ExHentai archive reservation is not active" }
		check(staging.isFile && staging.length() > 0L) { "No complete ExHentai archive to publish" }
		check(target.parentFile == target.parentFile?.canonicalFile &&
			staging.canonicalFile == staging) { "ExHentai destination changed" }
		// Never pass REPLACE_EXISTING. An occupied target is preserved.
		Files.move(staging.toPath(), target.toPath())
		isCommitted = true
	}

	override fun close() = synchronized(lock) {
		if (active.remove(this)) {
			if (staging.canonicalFile == staging) staging.delete()
			// Non-recursive: fails if another copy or staging file is present.
			if (createdDirectory != null && createdDirectory.canonicalFile == createdDirectory) createdDirectory.delete()
		}
		Unit
	}

	companion object {
		private val lock = Any()
		private val active = HashSet<ExhentaiNewArchive>()
		private const val MAX_CANDIDATES = 128

		/** Null chapterName denotes SINGLE_CBZ. Plain chapter files use a fresh title directory. */
		fun reserve(
			sourceDirectory: File,
			base: String,
			chapterName: String?,
			allowSharedDirectory: Boolean,
		): ExhentaiNewArchive = synchronized(lock) {
			require(base.isNotBlank() && base !in setOf(".", "..") && File(base).name == base)
			require(chapterName == null || File(chapterName).name == chapterName)
			val parent = sourceDirectory.canonicalFile
			check(parent.isDirectory) { "ExHentai source directory is unavailable" }
			for (i in 0 until MAX_CANDIDATES) {
				val stem = if (i == 0) base else "${base}_$i"
				val outerCbz = File(parent, "$stem.cbz")
				val folder = File(parent, stem)
				if (outerCbz.occupied() || active.any { it.target == outerCbz }) continue
				val target = if (chapterName == null) outerCbz else File(folder, chapterName)
				if (target.occupied() || active.any { it.target == target }) continue
				var createdDirectory: File? = null
				if (chapterName == null) {
					if (folder.occupied()) continue
				} else if (folder.occupied()) {
					if (!allowSharedDirectory || !folder.isDirectory || folder.canonicalFile != folder ||
						File(folder, LocalMangaOutput.ENTRY_NAME_INDEX).occupied()) continue
				} else {
					if (!folder.mkdir()) continue
					createdDirectory = folder
				}
				try {
					val staging = File.createTempFile(".miyorare-exhentai-", ".cbz.tmp", target.parentFile)
					val reservation = ExhentaiNewArchive(target, staging, createdDirectory)
					active.add(reservation)
					return@synchronized reservation
				} catch (e: IOException) {
					createdDirectory?.delete()
					throw e
				}
			}
			throw IOException("No safe destination for a new ExHentai archive")
		}

		private fun File.occupied(): Boolean = Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)
	}
}
