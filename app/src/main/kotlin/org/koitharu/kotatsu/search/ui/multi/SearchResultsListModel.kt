package org.koitharu.kotatsu.search.ui.multi

import android.content.Context
import androidx.annotation.StringRes
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.search.domain.LANGUAGE_LOCAL
import org.koitharu.kotatsu.search.domain.LANGUAGE_OTHER
import org.koitharu.kotatsu.search.domain.searchLanguageCode
import java.util.Locale

data class SearchResultsListModel(
	@StringRes val titleResId: Int,
	val source: MangaSource,
	val listFilter: MangaListFilter?,
	val sortOrder: SortOrder?,
	val list: List<MangaListModel>,
	val error: Throwable?,
	val isLoading: Boolean = false,
	val rank: Int = Int.MAX_VALUE,
) : ListModel {

	fun getBaseTitle(context: Context): String =
		if (titleResId != 0) context.getString(titleResId) else source.getTitle(context)

	fun getLanguageLabel(): String? = when (val code = source.searchLanguageCode()) {
		LANGUAGE_OTHER -> null
		LANGUAGE_LOCAL -> "LOCAL"
		else -> code.uppercase(Locale.ROOT)
	}

	internal val headerStatus: SearchSourceStatus
		get() = searchSourceStatus(isLoading, error != null)

	fun getTitle(context: Context): String {
		val baseTitle = getBaseTitle(context)
		if (titleResId != 0 || source === UnknownMangaSource) return baseTitle
		return listOfNotNull(baseTitle, getLanguageLabel(), if (isLoading) null else list.size.toString(), headerStatus.symbol)
			.joinToString(" · ")
	}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SearchResultsListModel && source == other.source && titleResId == other.titleResId
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		return if (
			previousState is SearchResultsListModel &&
			(previousState.list != list || previousState.error != error || previousState.isLoading != isLoading)
		) {
			ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
		} else {
			super.getChangePayload(previousState)
		}
	}
}

internal enum class SearchSourceStatus(val symbol: String) {
	LOADING("…"), ERROR("⚠"), COMPLETE("✓"),
}

internal fun searchSourceStatus(isLoading: Boolean, hasError: Boolean): SearchSourceStatus = when {
	isLoading -> SearchSourceStatus.LOADING
	hasError -> SearchSourceStatus.ERROR
	else -> SearchSourceStatus.COMPLETE
}
