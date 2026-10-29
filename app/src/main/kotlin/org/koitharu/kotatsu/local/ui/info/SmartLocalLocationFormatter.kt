package org.koitharu.kotatsu.local.ui.info

import java.net.URLDecoder

/**
 * Human-readable SAF location for display only. The original content URI remains the source
 * of truth for document access and persisted permissions.
 */
internal object SmartLocalLocationFormatter {
    fun format(raw: String): String {
        if (!raw.startsWith("content://")) return raw
        val encodedId = raw.substringAfter("/document/", "").substringBefore('/')
            .ifBlank { raw.substringAfter("/tree/", "").substringBefore('/') }
        val documentId = runCatching {
            URLDecoder.decode(encodedId.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(encodedId)
        if (documentId.isBlank()) return "Document provider"

        val volume = documentId.substringBefore(':', "")
        val relative = if (':' in documentId) documentId.substringAfter(':') else documentId
        val parts = relative.split('/').filter { it.isNotBlank() }
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "Internal storage"
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "SD card ($volume)"
            volume.isNotBlank() -> "Storage ($volume)"
            else -> "Document provider"
        }
        return (listOf(root) + parts).joinToString(" / ")
    }
}
