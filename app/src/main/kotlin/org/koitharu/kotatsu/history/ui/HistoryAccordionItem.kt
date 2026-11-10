package org.koitharu.kotatsu.history.ui

import android.content.Context
import androidx.core.os.ConfigurationCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation-only row. The History query windows and expansion keys remain unchanged. */
internal data class HistoryAccordionItem(
	val key: String,
	val kind: Kind,
	val expanded: Boolean = false,
	val titleCount: Int = 0,
	val groupCount: Int = 0,
	val date: LocalDate? = null,
	val month: YearMonth? = null,
	val daysAgo: Int = 0,
	val moreCount: Int = 0,
) : ListModel {

	enum class Kind { DAYS_BUCKET, OLDER_BUCKET, DAY, MONTH, MORE }

	override fun areItemsTheSame(other: ListModel): Boolean =
		other is HistoryAccordionItem && key == other.key && kind == other.kind

	fun title(context: Context): String {
		val locale = context.historyLocale()
		return when (kind) {
			Kind.DAYS_BUCKET -> context.getString(R.string.history_days_8_30)
			Kind.OLDER_BUCKET -> context.getString(R.string.history_older_than_30)
			Kind.DAY -> requireNotNull(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
			Kind.MONTH -> requireNotNull(month).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
			Kind.MORE -> context.resources.getQuantityString(
				R.plurals.history_accordion_show_more, moreCount, context.formattedHistoryCount(moreCount),
			)
		}
	}

	fun subtitle(context: Context): String? {
		val titles = context.resources.getQuantityString(
			R.plurals.history_accordion_titles, titleCount, context.formattedHistoryCount(titleCount),
		)
		return when (kind) {
			Kind.DAYS_BUCKET -> context.getString(R.string.history_accordion_summary, titles,
				context.resources.getQuantityString(R.plurals.history_accordion_active_days, groupCount,
					context.formattedHistoryCount(groupCount)))
			Kind.OLDER_BUCKET -> context.getString(R.string.history_accordion_summary, titles,
				context.resources.getQuantityString(R.plurals.history_accordion_active_months, groupCount,
					context.formattedHistoryCount(groupCount)))
			Kind.DAY -> context.getString(R.string.history_accordion_summary,
				DateTimeAgo.DaysAgo(daysAgo).format(context), titles)
			Kind.MONTH -> titles
			Kind.MORE -> null
		}
	}
}

internal fun nextHistoryAccordionMoreCount(remaining: Int): Int = remaining.coerceIn(0, 16)

private fun Context.historyLocale(): Locale =
	ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()

private fun Context.formattedHistoryCount(value: Int): String =
	NumberFormat.getIntegerInstance(historyLocale()).format(value)
