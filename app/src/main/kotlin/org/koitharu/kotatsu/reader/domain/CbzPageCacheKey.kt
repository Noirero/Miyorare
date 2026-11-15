package org.koitharu.kotatsu.reader.domain

import java.util.zip.ZipEntry

// A chapter can be replaced at the same path, with identical entry names, lengths and timestamps.
// ZIP central-directory metadata distinguishes changed pages without inflating them for cache hits.
internal fun cbzPageCacheKey(pageUrl: String, entry: ZipEntry): String =
	"cbz-page-v1:${entry.crc}:${entry.size}:$pageUrl"
