package org.koitharu.kotatsu.backup.local.domain

import android.content.Context
import android.net.Uri
import androidx.annotation.CheckResult
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.buffer
import okio.sink
import okio.source
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.Date
import javax.inject.Inject

class ExternalBackupStorage @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	suspend fun list(): List<BackupFile> = runInterruptible(Dispatchers.IO) {
		getRootOrThrow().listFiles().mapNotNull { df ->
			val name = df.name ?: return@mapNotNull null
			if (!df.isFile || !df.canRead()) return@mapNotNull null
			val date = BackupUtils.parseBackupDateTime(name) ?: return@mapNotNull null
			BackupFile(uri = df.uri, name = name, dateTime = date)
		}.sortedDescending()
	}

	suspend fun listOrNull(): List<BackupFile>? = runCatchingCancellable {
		list()
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrNull()

	suspend fun put(file: File): Uri = runInterruptible(Dispatchers.IO) {
		val out = checkNotNull(
			getRootOrThrow().createFile(BackupUtils.MIME_TYPE, file.name),
		) { "Cannot create target backup file" }
		try {
			checkNotNull(context.contentResolver.openOutputStream(out.uri, "wt")).sink().buffer().use { sink ->
				file.source().buffer().use { src ->
					sink.writeAll(src)
				}
			}
			out.uri
		} catch (e: Throwable) {
			runCatching { out.delete() }.onFailure(e::addSuppressed)
			throw e
		}
	}

	@CheckResult
	suspend fun delete(victim: BackupFile): Boolean = runInterruptible(Dispatchers.IO) {
		val df = DocumentFile.fromSingleUri(context, victim.uri)
		df != null && df.delete()
	}

	suspend fun getLastBackupDate(): Date? = listOrNull()?.maxOfOrNull { it.dateTime }

	suspend fun trim(maxCount: Int): Boolean {
		if (maxCount == Int.MAX_VALUE) return false
		val list = listOrNull() ?: return false
		if (list.size <= maxCount) return false
		var result = false
		for (i in maxCount until list.size) {
			if (delete(list[i])) {
				result = true
			}
		}
		return result
	}

	@Blocking
	private fun getRootOrThrow(): DocumentFile {
		val uri = checkNotNull(settings.periodicalBackupDirectory) {
			"Backup directory is not specified"
		}
		val root = DocumentFile.fromTreeUri(context, uri)
		return checkNotNull(root) { "Cannot obtain DocumentFile from $uri" }
	}
}
