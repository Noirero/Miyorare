package org.koitharu.kotatsu.core.logs

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the most recent abnormal-exit report on disk so a crash/ANR can still be inspected after
 * the app process has already died and DropSauce is opened again.
 *
 * Regular Java/Kotlin crashes are supplied by ACRA. On Android 11+ we additionally inspect
 * ApplicationExitInfo for ANRs and native crashes, which do not necessarily reach ACRA's uncaught
 * exception handler.
 */
object CrashLogStore {

	private const val DIRECTORY = "diagnostics"
	private const val LAST_LOG = "last_abnormal_exit.log"
	private const val PENDING_LOG = "pending_abnormal_exit.log"
	private const val LAST_EXIT_TIMESTAMP = "last_system_exit_timestamp"
	private const val MAX_TRACE_CHARS = 256 * 1024

	fun saveAcraCrash(context: Context, reportJson: String) {
		val text = buildString {
			appendLine("Miyorare Beta abnormal exit log")
			appendLine("Type: Java/Kotlin crash (ACRA)")
			appendLine("Captured: ${formatTimestamp(System.currentTimeMillis())}")
			appendLine()
			append(reportJson)
		}
		writeLog(context, text)
	}

	/** Capture the newest ANR/native crash once. Safe to call on every normal-process startup. */
	fun capturePreviousSystemExit(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
		val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
		val exits = runCatching {
			activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
		}.getOrNull().orEmpty()
		val exit = exits
			.asSequence()
			.filter {
				it.reason == android.app.ApplicationExitInfo.REASON_ANR ||
					it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
			}
			.maxByOrNull { it.timestamp }
			?: return

		val lastSeen = runCatching {
			markerFile(context).takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: 0L
		}.getOrDefault(0L)
		if (exit.timestamp <= lastSeen) return

		val type = when (exit.reason) {
			android.app.ApplicationExitInfo.REASON_ANR -> "Freeze / ANR"
			android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
			else -> "Abnormal exit"
		}
		val trace = runCatching {
			exit.traceInputStream?.bufferedReader()?.use(::readLimited)
		}.getOrNull()

		val text = buildString {
			appendLine("Miyorare Beta abnormal exit log")
			appendLine("Type: $type")
			appendLine("Occurred: ${formatTimestamp(exit.timestamp)}")
			appendLine("Process: ${exit.processName}")
			appendLine("Status: ${exit.status}")
			appendLine("Importance: ${exit.importance}")
			exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
			appendLine("PSS: ${exit.pss} kB")
			appendLine("RSS: ${exit.rss} kB")
			if (!trace.isNullOrBlank()) {
				appendLine()
				appendLine("--- System trace ---")
				append(trace)
			}
		}

		// Mark the system exit as consumed only after the report itself was persisted. If storage is
		// temporarily unavailable, the same exit can be recovered again on a later app launch.
		if (writeLog(context, text)) {
			runCatching {
				markerFile(context).apply { parentFile?.mkdirs() }.writeText(exit.timestamp.toString())
			}
		}
	}

	fun pendingLog(context: Context): String? = runCatching {
		pendingFile(context)
			.takeIf(File::isFile)
			?.readText()
	}.getOrNull()?.takeIf { it.isNotBlank() }

	fun clearPending(context: Context) {
		runCatching { pendingFile(context).delete() }
	}

	fun lastLog(context: Context): String? = runCatching {
		lastLogFile(context)
			.takeIf(File::isFile)
			?.readText()
	}.getOrNull()?.takeIf { it.isNotBlank() }

	/**
	 * Writes the complete recovered report to a user-selected document Uri as UTF-8 text.
	 * Prefer the pending report shown in the dialog, then fall back to the retained last report.
	 */
	fun exportText(context: Context, uri: Uri): Boolean {
		val text = pendingLog(context) ?: lastLog(context) ?: return false
		return runCatching {
			val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
			stream.bufferedWriter(Charsets.UTF_8).use { writer ->
				writer.write(text)
			}
			true
		}.getOrDefault(false)
	}

	fun suggestedTextFileName(): String {
		val suffix = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
		return "Miyorare-Beta-crash-log-$suffix.txt"
	}

	private fun writeLog(context: Context, text: String): Boolean = runCatching {
		val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
		File(dir, LAST_LOG).writeText(text)
		File(dir, PENDING_LOG).writeText(text)
		true
	}.getOrDefault(false)

	private fun pendingFile(context: Context) = File(File(context.filesDir, DIRECTORY), PENDING_LOG)
	private fun lastLogFile(context: Context) = File(File(context.filesDir, DIRECTORY), LAST_LOG)
	private fun markerFile(context: Context) = File(File(context.filesDir, DIRECTORY), LAST_EXIT_TIMESTAMP)

	private fun formatTimestamp(timestamp: Long): String =
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))

	private fun readLimited(reader: BufferedReader): String {
		val out = StringBuilder(minOf(16 * 1024, MAX_TRACE_CHARS))
		val buffer = CharArray(4096)
		while (out.length < MAX_TRACE_CHARS) {
			val remaining = MAX_TRACE_CHARS - out.length
			val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
			if (count <= 0) break
			out.append(buffer, 0, count)
		}
		if (reader.read() != -1) out.append("\n... trace truncated ...")
		return out.toString()
	}
}
