package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deterministic Phase 10 contract checks.
 *
 * These tests intentionally validate every registered theme while keeping golden visual coverage
 * representative. Device/render evidence remains a separate Android CI responsibility.
 */
class ReaderJourneyPhase10ValidationTest {

	@Test
	fun `Indonesian locale covers every Reader Journey string with matching format placeholders`() {
		val base = source("res/values/strings.xml")
		val indonesian = source("res/values-in/strings.xml")
		val stringRegex = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", setOf(RegexOption.DOT_MATCHES_ALL))

		fun parse(xml: String): Map<String, String> = stringRegex.findAll(xml)
			.associate { match -> match.groupValues[1] to match.groupValues[2] }

		val baseStrings = parse(base)
		val idStrings = parse(indonesian)
		val readerJourneyKeys = baseStrings.keys
			.filter { key -> key == "reader_journey" || key.startsWith("reader_journey_") }
			.sorted()

		val missing = readerJourneyKeys.filterNot(idStrings::containsKey)
		assertTrue("Missing Indonesian Reader Journey strings: $missing", missing.isEmpty())
		assertEquals("Perjalanan Pembaca", idStrings["reader_journey"])

		val placeholderRegex = Regex("""%(?:\\d+\\$)?[dsf]""")
		for (key in readerJourneyKeys) {
			val expected = placeholderRegex.findAll(baseStrings.getValue(key)).map { it.value }.sorted().toList()
			val actual = placeholderRegex.findAll(idStrings.getValue(key)).map { it.value }.sorted().toList()
			assertEquals("Format placeholders changed for $key", expected, actual)
		}
	}

	@Test
	fun `all 12 themes pass the Light Dark OLED semantic matrix`() {
		assertTrue(RankThemeRegistry.validate().isEmpty())
		assertEquals(12, RankThemeRegistry.definitions.size)

		RankThemeRegistry.definitions.forEach { definition ->
			RankThemeVariant.entries.forEach { variant ->
				val tokens = definition.tokens(variant)
				assertNotEquals(
					"background/surface collapsed for ${definition.id.stableId} ${variant.name}",
					tokens.background,
					tokens.surface,
				)
				assertNotEquals(tokens.errorColor, tokens.successColor)
				assertNotEquals(tokens.warningColor, tokens.successColor)
				assertNotEquals(tokens.destructiveColor, tokens.successColor)
				assertNotEquals(tokens.primaryAccent, tokens.errorColor)
				assertNotEquals(tokens.primaryAccent, tokens.destructiveColor)
				assertNotEquals(tokens.primaryAccent, tokens.disabledColor)
				assertNotEquals(tokens.focusIndicatorColor, tokens.disabledColor)

				listOf(
					tokens.background,
					tokens.surface,
					tokens.primaryAccent,
					tokens.secondaryAccent,
					tokens.onAccent,
					tokens.errorColor,
					tokens.warningColor,
					tokens.successColor,
					tokens.destructiveColor,
					tokens.disabledColor,
					tokens.focusIndicatorColor,
				).forEach { color ->
					assertEquals(
						"Expected opaque ARGB for ${definition.id.stableId} ${variant.name}",
						0xFFL,
						(color ushr 24) and 0xFFL,
					)
				}

				if (variant == RankThemeVariant.OLED) {
					assertEquals(0xFF000000L, tokens.background)
					assertTrue(
						"OLED surface must remain near-black for ${definition.id.stableId}",
						red(tokens.surface) <= 16 &&
							green(tokens.surface) <= 16 &&
							blue(tokens.surface) <= 16,
					)
				}
			}
		}
	}

	@Test
	fun `representative golden themes stay frozen and have full visual specs`() {
		val representatives = listOf(
			RankThemeId.FIRST_PAGE,
			RankThemeId.NEON_ARCHIVE,
			RankThemeId.GOLDEN_MANUSCRIPT,
			RankThemeId.ETERNAL_LIBRARY,
		)
		assertEquals(
			listOf(
				"NEWCOMER_FIRST_PAGE",
				"ARCHIVIST_NEON_ARCHIVE",
				"MASTER_GOLDEN_MANUSCRIPT",
				"LEGEND_ETERNAL_LIBRARY",
			),
			representatives.map { it.stableId },
		)
		representatives.forEach { theme ->
			assertNotNull(RankThemeVisualRegistry.resolve(theme))
			RankThemeVariant.entries.forEach { variant ->
				assertNotNull(RankThemeRegistry.resolve(theme.stableId)?.tokens(variant))
			}
		}
	}

	@Test
	fun `developer gallery contains Phase 10 accessibility and long-label probes`() {
		val gallery = source("kotlin/org/koitharu/kotatsu/settings/developer/RankThemeGalleryFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(gallery.contains("developer_theme_long_indonesian_sample"))
		assertTrue(gallery.contains("RankThemeVariant.entries"))
		assertTrue(gallery.contains("if(wallpaperEnabled)"))
		assertTrue(gallery.contains("shape=RoundedCornerShape(if(selected)"))
		assertTrue(gallery.contains("if(selected)2.dpelse1.dp"))
		assertTrue(gallery.contains("fontWeight=if(selected)FontWeight.BoldelseFontWeight.Normal"))
		assertTrue(gallery.contains("visualPalette.success"))
		assertTrue(gallery.contains("visualPalette.warning"))
		assertTrue(gallery.contains("visualPalette.error"))
	}

	@Test
	fun `system bar and edge to edge wiring stays present`() {
		val baseActivity = source("kotlin/org/koitharu/kotatsu/core/ui/BaseActivity.kt")
			.replace(Regex("\\s+"), "")
		val settingsActivity = source("kotlin/org/koitharu/kotatsu/settings/SettingsActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(baseActivity.contains("enableEdgeToEdge()"))
		assertTrue(baseActivity.contains("WindowCompat.getInsetsController(window,window.decorView)"))
		assertTrue(baseActivity.contains("WindowInsetsCompat.Type.statusBars()"))
		assertTrue(baseActivity.contains("WindowInsetsCompat.Type.systemBars()"))
		assertTrue(settingsActivity.contains("insets.getInsets(WindowInsetsCompat.Type.systemBars())"))
		assertTrue(settingsActivity.contains("setStatusBarScrimColor(chromeSurface)"))
	}

	@Test
	fun `Compose and legacy surfaces resolve the same Reader Journey presentation source`() {
		val composeTheme = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")
			.replace(Regex("\\s+"), "")
		val legacyPalette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(composeTheme.contains("journeyThemeRuntime?.state?.collectAsState()"))
		assertTrue(composeTheme.contains("journeyThemeRuntimeState.resolveTokens("))
		assertTrue(legacyPalette.contains("readerJourneyThemeRuntimeOrNull()?.state?.value"))
		assertTrue(legacyPalette.contains("rankThemeState?.resolveTokens("))
		assertFalse(composeTheme.contains("RankThemeId.NEON_ARCHIVE"))
		assertFalse(legacyPalette.contains("RankThemeId.NEON_ARCHIVE"))
	}

	private fun red(color: Long): Int = ((color ushr 16) and 0xFF).toInt()
	private fun green(color: Long): Int = ((color ushr 8) and 0xFF).toInt()
	private fun blue(color: Long): Int = (color and 0xFF).toInt()

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
