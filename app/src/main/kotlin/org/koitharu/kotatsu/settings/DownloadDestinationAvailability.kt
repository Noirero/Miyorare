package org.koitharu.kotatsu.settings

import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File

internal enum class DownloadDestinationAvailability {
	AVAILABLE, UNAVAILABLE, READ_ONLY,
}

/** Read-only probe: a missing downloads child can be created by the worker under a writable root. */
internal fun downloadDestinationAvailability(root: File?): DownloadDestinationAvailability {
	if (root == null || !root.isDirectory || !root.canRead()) return DownloadDestinationAvailability.UNAVAILABLE
	val downloads = File(root, LocalMangaOutput.DOWNLOADS_DIR_NAME)
	if (downloads.exists() && (!downloads.isDirectory || !downloads.canRead())) {
		return DownloadDestinationAvailability.UNAVAILABLE
	}
	if (!root.canWrite() || (downloads.exists() && !downloads.canWrite())) {
		return DownloadDestinationAvailability.READ_ONLY
	}
	return DownloadDestinationAvailability.AVAILABLE
}
