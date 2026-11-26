package org.koitharu.kotatsu.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.PrivateFavouritesVisualResolver
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesProtection
import org.koitharu.kotatsu.settings.compose.LocalPrivateSettingsThemeSnapshot
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import org.koitharu.kotatsu.settings.compose.PrivateSettingsThemeSnapshot
import org.koitharu.kotatsu.settings.compose.PrivateSanctuaryTreatment
import org.koitharu.kotatsu.settings.compose.SanctuaryRole
import org.koitharu.kotatsu.settings.compose.sanctuaryAssets
import kotlin.math.abs
import kotlin.math.roundToInt

/** Synthetic presentation fixtures only: no changes to security stores or real Private data. */
class PrivateSanctuaryRuntimeTest {
	@get:Rule val compose = createComposeRule()
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun actualSettingsRowsKeepThemeSelectionAndControlledInteractions() {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = MiyorareAppearance.KEY_DESIGN_STYLE
		val old = prefs.getString(key, null)
		val theme = mutableStateOf(PrivateFavouritesThemePreset.MIYORARE)
		val protection = mutableStateOf(PrivateFavouritesProtection.NONE)
		val allow = mutableStateOf(false)
		var screenshotCallbacks = 0
		var pinCallbacks = 0
		try {
			prefs.edit().putString(key, MiyorareDesignStyle.MODERN.name).commit()
			compose.setContent {
				MiyorareSettingsTheme("private") {
					CompositionLocalProvider(
						LocalPrivateSettingsThemeSnapshot provides PrivateSettingsThemeSnapshot(theme.value, true, VisualEffectLevel.BALANCED),
					) {
						PrivateFavouritesSettingsScreen(
							protection = protection.value, hasPin = false, includeBackup = false,
							allowScreenshots = allow.value, privateTheme = theme.value, privatePagesDirectory = null,
							onThemeClick = {}, onThemeSelect = { theme.value = it }, onProtectionClick = {},
							onChangePin = { pinCallbacks++ }, onLockNow = {}, onIncludeBackupChange = {},
							onAllowScreenshotsChange = { screenshotCallbacks++ },
							onPrivatePagesDirectoryClick = {}, onDisablePrivate = {},
						)
					}
				}
			}
			val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
			for (preset in PrivateFavouritesThemePreset.entries.filter { it != PrivateFavouritesThemePreset.FOLLOW_NORMAL }) {
				compose.runOnIdle { theme.value = preset }
				compose.onNode(selected and hasText(context.getString(preset.titleResId))).assertExists()
			}
			val changePin = context.getString(R.string.private_favourites_change_pin)
			compose.onNodeWithText(changePin).assertDoesNotExist()
			compose.runOnIdle { protection.value = PrivateFavouritesProtection.PIN }
			compose.onNodeWithText(changePin).performScrollTo().performTouchInput { click() }
			compose.runOnIdle { assertEquals(0, pinCallbacks) }
			val screenshots = context.getString(R.string.private_favourites_allow_screenshots)
			compose.onNodeWithText(screenshots).performScrollTo().performClick()
			compose.runOnIdle { assertEquals(1, screenshotCallbacks) }
			// A rejected/cancelled request leaves the controlled switch unchanged.
			val toggle = compose.onAllNodes(isToggleable(), useUnmergedTree = true)[0]
			toggle.assertIsOff().performClick()
			compose.runOnIdle {
				assertEquals(2, screenshotCallbacks)
				allow.value = true
			}
			toggle.assertIsOn()
		} finally {
			prefs.edit().apply { if (old == null) remove(key) else putString(key, old) }.commit()
		}
	}

	@Test
	fun protectionLandscapeBlendsIntoCardWithoutSeamInAllThemesAndDirections() {
		val theme = mutableStateOf(PrivateFavouritesThemePreset.MIYORARE)
		val direction = mutableStateOf(LayoutDirection.Ltr)
		compose.setContent {
			// Deliberately high-contrast synthetic artwork; no Private data or secure-window bypass.
			val landscape = remember {
				android.graphics.Bitmap.createBitmap(64, 24, android.graphics.Bitmap.Config.ARGB_8888)
					.apply { eraseColor(android.graphics.Color.WHITE) }.asImageBitmap()
			}
			val spec = requireNotNull(PrivateFavouritesVisualResolver.resolve(theme.value))
			CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
				Column {
					for ((tag, image) in listOf("protection-art" to landscape, "protection-base" to null)) {
						val treatment = PrivateSanctuaryTreatment(
							spec, null, image, 1f, VisualEffectLevel.FULL, Color.Red,
						)
						Box(treatment.surface(SanctuaryRole.PROTECTION)(
							Modifier.size(320.dp, 64.dp).testTag(tag), RectangleShape, 0f,
						))
					}
				}
			}
		}
		fun difference(a: Color, b: Color): Float =
			maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

		for (preset in PrivateFavouritesThemePreset.entries.filter { it != PrivateFavouritesThemePreset.FOLLOW_NORMAL }) {
			for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
				compose.runOnIdle { theme.value = preset; direction.value = layout }
				val art = compose.onNodeWithTag("protection-art").captureToImage().toPixelMap()
				val base = compose.onNodeWithTag("protection-base").captureToImage().toPixelMap()
				assertEquals(base.width, art.width)
				assertEquals(base.height, art.height)
				val imageWidth = (art.width * 0.60f).roundToInt()
				val rtl = layout == LayoutDirection.Rtl
				val seam = if (rtl) imageWidth - 1 else art.width - imageWidth
				val y = art.height / 2
				for (offset in 0..2) {
					val x = seam + if (rtl) -offset else offset
					assertTrue(
						"$preset $layout: artwork must match the underlying surface at its leading edge",
						difference(art[x, y], base[x, y]) <= 0.025f,
					)
				}
				val far = (art.width * if (rtl) 0.1f else 0.9f).roundToInt()
				assertTrue(
					"$preset $layout: artwork must remain visible at the trailing edge",
					difference(art[far, y], base[far, y]) > 0.05f,
				)
			}
		}
	}

	@Test
	fun allApprovedResourcesDecodeAtTheirLockedDimensions() {
		for (theme in PrivateFavouritesThemePreset.entries.filter { it != PrivateFavouritesThemePreset.FOLLOW_NORMAL }) {
			val pair = requireNotNull(sanctuaryAssets(theme))
			val ornament = requireNotNull(android.graphics.BitmapFactory.decodeResource(context.resources, pair.ornament))
			val landscape = requireNotNull(android.graphics.BitmapFactory.decodeResource(context.resources, pair.landscape))
			try {
				assertEquals(256, ornament.width)
				assertEquals(256, ornament.height)
				assertTrue(ornament.hasAlpha())
				assertEquals(640, landscape.width)
				assertEquals(240, landscape.height)
			} finally {
				ornament.recycle()
				landscape.recycle()
			}
		}
	}
}
