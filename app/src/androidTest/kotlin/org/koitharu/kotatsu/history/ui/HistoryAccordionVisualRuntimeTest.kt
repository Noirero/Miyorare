package org.koitharu.kotatsu.history.ui

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.shape.MaterialShapeDrawable
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.time.LocalDate
import java.util.Locale

/** Real Android View binding, semantic color, locale and screenshot probes for History only. */
@RunWith(AndroidJUnit4::class)
class HistoryAccordionVisualRuntimeTest {

	private data class Scene(
		val tag: String,
		val nightMode: Int,
		val locale: Locale,
		val preset: MiyorareThemePreset,
		val fontScale: Float,
	)

	@Test
	fun themeAndLocaleAccordionBindsWithoutHardcodedColors() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val app = instrumentation.targetContext
		val preferences = PreferenceManager.getDefaultSharedPreferences(app)
		val keys = listOf(
			MiyorareAppearance.KEY_DESIGN_STYLE,
			MiyorareAppearance.KEY_THEME_PRESET,
			MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE,
		)
		val saved = keys.associateWith { preferences.getString(it, null) }
		try {
			instrumentation.runOnMainSync {
				val scenes = listOf(
					Scene("en-light", Configuration.UI_MODE_NIGHT_NO, Locale.ENGLISH, MiyorareThemePreset.MIYORARE, 1f),
					Scene("id-dark", Configuration.UI_MODE_NIGHT_YES, Locale.forLanguageTag("id-ID"), MiyorareThemePreset.CYAN, 1f),
					Scene("en-dark-custom-large-font", Configuration.UI_MODE_NIGHT_YES, Locale.ENGLISH, MiyorareThemePreset.CUSTOM, 1.5f),
				)
				val paletteIds = mutableSetOf<Int>()
				for (scene in scenes) {
					preferences.edit()
						.putString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.MODERN.name)
						.putString(MiyorareAppearance.KEY_THEME_PRESET, scene.preset.name)
						.putString(MiyorareAppearance.KEY_MODERN_PALETTE_SOURCE, MiyorareModernPaletteSource.MATCH_THEME.name)
						.commit()
					val configuration = Configuration(app.resources.configuration).apply {
						uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or scene.nightMode
						setLocale(scene.locale)
						fontScale = scene.fontScale
					}
					val themed = ContextThemeWrapper(app.createConfigurationContext(configuration),
						R.style.Theme_Kotatsu).apply {
						theme.applyStyle(R.style.ThemeOverlay_MiyorareModern, true)
					}
					val palette = requireNotNull(themed.miyorareViewPaletteFromPreferences())
					paletteIds += palette.primary
					val clicked = mutableListOf<HistoryHeaderAction>()
					val rows: List<ListModel> = listOf(
						HistoryAccordionItem(key = "bucket:middle", kind = HistoryAccordionItem.Kind.DAYS_BUCKET,
							expanded = true, titleCount = 120, groupCount = 6),
						HistoryAccordionItem(key = "day:2026-11-02", kind = HistoryAccordionItem.Kind.DAY,
							expanded = true, titleCount = 120, date = LocalDate.of(2026, 11, 2), daysAgo = 8),
						HistoryAccordionItem(key = "day:2026-11-02", kind = HistoryAccordionItem.Kind.MORE,
							moreCount = 50),
					)
					val adapter = ListDelegationAdapter(historyAccordionItemAD({ clicked += it }, false))
					adapter.items = rows
					val holderViews = rows.indices.map { index ->
						adapter.createViewHolder(FrameLayout(themed), adapter.getItemViewType(index)).also {
							adapter.bindViewHolder(it, index)
						}.itemView
					}
					val parent = holderViews[0]
					val day = holderViews[1]
					val more = holderViews[2]
					assertEquals(palette.primaryContainer,
						(parent.background as MaterialShapeDrawable).fillColor?.defaultColor)
					assertEquals(palette.surfaceContainerHigh,
						(day.background as MaterialShapeDrawable).fillColor?.defaultColor)
					assertEquals(palette.onPrimaryContainer,
						parent.findViewById<TextView>(R.id.text_view_title).currentTextColor)
					assertEquals(palette.onSurface,
						day.findViewById<TextView>(R.id.text_view_title).currentTextColor)
					assertEquals(themed.getString(R.string.history_days_8_30),
						parent.findViewById<TextView>(R.id.text_view_title).text.toString())
					assertEquals(rows[1].let { (it as HistoryAccordionItem).subtitle(themed) },
						day.findViewById<TextView>(R.id.text_view_subtitle).text.toString())
					assertEquals(rows[2].let { (it as HistoryAccordionItem).title(themed) },
						more.findViewById<TextView>(R.id.text_view_title).text.toString())
					assertTrue(parent.performClick())
					assertTrue(more.performClick())
					assertEquals(listOf("bucket:middle", "day:2026-11-02"), clicked.map { it.key })
					assertEquals(listOf(false, true), clicked.map { it.loadMore })

					val column = LinearLayout(themed).apply {
						orientation = LinearLayout.VERTICAL
						setBackgroundColor(palette.surface)
					}
					for (row in holderViews) {
						column.addView(row, LinearLayout.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
					}
					val width = (320 * themed.resources.displayMetrics.density).toInt()
					column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
					column.layout(0, 0, column.measuredWidth, column.measuredHeight)
					assertTrue(column.measuredHeight > 0)
					// Screenshots are synthetic UI rows from the real Android renderer, never user history.
					runCatching { saveScreenshot(app, column, scene.tag) }
						.onFailure { Log.w("HistoryAccordionVisual", "Screenshot unavailable for ${scene.tag}", it) }
				}
				assertTrue("Different theme presets should produce different semantic primary colors",
					paletteIds.size > 1)
			}
		} finally {
			val editor = preferences.edit()
			saved.forEach { (key, value) ->
				if (value == null) editor.remove(key) else editor.putString(key, value)
			}
			editor.commit()
		}
	}

	private fun saveScreenshot(context: android.content.Context, view: View, tag: String) {
		val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
		try {
			view.draw(Canvas(bitmap))
			val values = ContentValues().apply {
				put(MediaStore.MediaColumns.DISPLAY_NAME, "history-accordion-$tag.png")
				put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
				put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/miyorare-category-sets-ui")
				put(MediaStore.MediaColumns.IS_PENDING, 1)
			}
			val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
			try {
				context.contentResolver.openOutputStream(uri)?.use { stream ->
					check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
				} ?: error("Unable to open screenshot output")
			} finally {
				val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
				context.contentResolver.update(uri, complete, null, null)
			}
		} finally {
			bitmap.recycle()
		}
	}
}
