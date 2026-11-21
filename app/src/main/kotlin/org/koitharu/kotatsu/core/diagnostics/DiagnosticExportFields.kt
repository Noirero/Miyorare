package org.koitharu.kotatsu.core.diagnostics

/** The existing layout producers format dimensions as text. Only these code-owned fields may be exported. */
internal object DiagnosticExportFields {
	private val layoutEvents = setOf("viewport_layout", "scroll_idle")
	private val contentEmission = Regex("content_emit_[0-9]{1,8}")
	private val pagerEvents = setOf(
		"inner_width_constraint", "pager_layout", "page_selected", "page_scroll_state", "set_switch_requested",
		"categories_applied", "fragment_create", "categories_submit", "categories_commit",
	)
	private val numericFields = setOf(
		"viewport_width", "viewport_height", "pager_width", "pager_height", "pager_w", "inner_w", "inner_m",
		"page_w", "page_m", "span", "first_visible", "scroll_y", "adapter_count", "old_lp", "new_lp", "android",
		"page", "tabs", "created", "position", "selected_events", "scroll_state", "state", "child_fragments",
		"pager_index", "category_count", "count", "unique_positions", "old_count", "new_count", "commit_ms", "callback_ms",
	)
	private val booleanFields = setOf("restored_type", "structure_changed", "active_removed")

	fun layout(tag: String, event: String, detail: String): String? {
		val allowed = when (tag) {
			"FavouritesLayout" -> event in layoutEvents || contentEmission.matches(event)
			"FavouritesPager" -> event in pagerEvents
			else -> false
		}
		if (!allowed) return null
		return detail.split(' ').mapNotNull { field ->
			val name = field.substringBefore('=')
			val value = field.substringAfter('=', "")
			when {
				name in numericFields -> value.toLongOrNull()?.let { "$name=$it" }
				name in booleanFields && (value == "true" || value == "false") -> field
				name == "orientation" && (value == "portrait" || value == "landscape") -> field
				else -> null
			}
		}.joinToString(" ").takeIf { it.isNotBlank() }
	}
}

internal enum class CoverEvent { BIND, START, SUCCESS, ERROR, CANCEL, PENDING_VISIBLE }
internal enum class PageRoute { CACHE, CBZ, SAF, FILE, PDF, NETWORK }
internal enum class ReaderEvent { LOCAL_SELECTED, REMOTE_SELECTED, EMPTY_LOCAL_FALLBACK, FAILED_LOCAL_FALLBACK, FIRST_PAGE_READY }
internal enum class DownloadEvent { PAGES_RESOLVED, TRANSFER_FINISHED, ARCHIVE_INDEXED, ARCHIVE_VERIFIED, ARCHIVE_PAGE_COUNT_MISMATCH, ARCHIVE_UNREADABLE }
