package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R

/**
 * Small self-contained help affordance for the Explore source-provenance filters.
 *
 * Keeping the click behavior inside the view lets the filter adapter remain presentation-only and
 * avoids adding another Fragment/navigation dependency just to explain the provenance badges.
 */
class ExploreSourceProvenanceInfoButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AppCompatImageButton(context, attrs, defStyleAttr) {

	init {
		setOnClickListener { showProvenanceInfo() }
	}

	private fun showProvenanceInfo() {
		val horizontalPadding = dp(24)
		val verticalPadding = dp(8)
		val rowGap = dp(10)
		val badgeWidth = dp(120)

		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
		}

		PROVENANCE_INFO_ROWS.forEachIndexed { index, row ->
			val line = LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				if (index > 0) {
					setPadding(0, rowGap, 0, 0)
				}
			}
			val badge = TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(badgeWidth, dp(30))
				gravity = Gravity.CENTER
				setPadding(dp(8), 0, dp(8), 0)
				textSize = 12f
				setBackgroundResource(R.drawable.bg_extension_store_kind)
				bindExploreSourceOriginBadge(row.origin)
			}
			val description = TextView(context).apply {
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
					it.marginStart = dp(12)
				}
				setText(row.descriptionRes)
				textSize = 14f
				setTextColor(
					MaterialColors.getColor(
						this,
						com.google.android.material.R.attr.colorOnSurfaceVariant,
					),
				)
			}
			line.addView(badge)
			line.addView(description)
			content.addView(line)
		}

		val scrollView = ScrollView(context).apply {
			addView(content)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.explore_source_badge_info_title)
			.setView(scrollView)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal fun TextView.bindExploreSourceOriginBadge(origin: ExploreSourceOrigin) {
	setText(origin.titleRes())
	val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
	val base = origin.identityColor()
	val surfaceMix = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.22f else 0.08f
	val backgroundColor = ColorUtils.blendARGB(base, surface, surfaceMix)
	backgroundTintList = ColorStateList.valueOf(backgroundColor)

	val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, backgroundColor)
	val blackContrast = ColorUtils.calculateContrast(Color.BLACK, backgroundColor)
	setTextColor(if (whiteContrast >= blackContrast) Color.WHITE else Color.BLACK)
}

internal fun formatExploreSourceListSummary(
	origin: ExploreSourceOrigin,
	rawSummary: String?,
	compactSummary: String?,
): String? {
	if (origin != ExploreSourceOrigin.EXTENSION_STORE) return compactSummary
	val parts = rawSummary
		?.split(" • ")
		?.map { it.trim() }
		?.filter { it.isNotBlank() }
		.orEmpty()
	if (parts.size < 3) return rawSummary?.takeIf { it.isNotBlank() } ?: compactSummary

	val language = parts.first()
	val version = parts[1].removePrefix("v").removePrefix("V").trim()
	val repository = parts.drop(2).joinToString(" • ")
	if (version.isBlank() || repository.isBlank()) return rawSummary?.takeIf { it.isNotBlank() } ?: compactSummary
	return "$language • $repository • v$version"
}

private fun ExploreSourceOrigin.identityColor(): Int = when (this) {
	ExploreSourceOrigin.MIYORARE_PACK -> Color.rgb(35, 126, 224)
	ExploreSourceOrigin.EXTENSION_STORE -> Color.rgb(12, 151, 105)
	ExploreSourceOrigin.UMA -> Color.rgb(210, 66, 102)
	ExploreSourceOrigin.GEKKOUSHI -> Color.rgb(111, 76, 213)
	ExploreSourceOrigin.LNREADER -> Color.rgb(230, 122, 32)
	ExploreSourceOrigin.CUSTOM_PLUGIN -> Color.rgb(96, 109, 133)
}

private data class ProvenanceInfoRow(
	val origin: ExploreSourceOrigin,
	val descriptionRes: Int,
)

private val PROVENANCE_INFO_ROWS = listOf(
	ProvenanceInfoRow(ExploreSourceOrigin.MIYORARE_PACK, R.string.explore_source_badge_info_miyorare),
	ProvenanceInfoRow(ExploreSourceOrigin.EXTENSION_STORE, R.string.explore_source_badge_info_store),
	ProvenanceInfoRow(ExploreSourceOrigin.UMA, R.string.explore_source_badge_info_uma),
	ProvenanceInfoRow(ExploreSourceOrigin.GEKKOUSHI, R.string.explore_source_badge_info_gekkoushi),
	ProvenanceInfoRow(ExploreSourceOrigin.LNREADER, R.string.explore_source_badge_info_lnreader),
	ProvenanceInfoRow(ExploreSourceOrigin.CUSTOM_PLUGIN, R.string.explore_source_badge_info_custom),
)
