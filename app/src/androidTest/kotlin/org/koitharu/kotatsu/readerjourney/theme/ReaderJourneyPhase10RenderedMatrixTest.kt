package org.koitharu.kotatsu.readerjourney.theme

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyChapterEntity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.settings.AppearanceSettingsFragment
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.stats.ui.StatsActivity
import java.io.OutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReaderJourneyPhase10RenderedMatrixTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var database: MangaDatabase

    @Inject
    lateinit var profileStore: ReaderProfileStore

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val scenario: String get() = arguments.getString(ARG_SCENARIO) ?: "unknown"
    private val themeMode: String get() = arguments.getString(ARG_THEME) ?: "dark"

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking { database.clearAllTables() }
        profileStore.updateCosmetics(ReaderJourneyCosmeticLoadout())
        runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }

        settings.isOnboardingCompleted = true
        settings.setMiyorareDesignStyle(MiyorareDesignStyle.MODERN)
        settings.setMiyorareThemePreset(MiyorareThemePreset.MIYORARE)
        settings.setTheme(
            if (themeMode == "light") AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES,
        )
        settings.setAmoledTheme(themeMode == "oled")

        val minimal = scenario.contains("minimal")
        val reduced = scenario.contains("reduced") || minimal
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(AppSettings.KEY_READER_JOURNEY_ENABLED, true)
            .putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, reduced)
            .putBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, reduced)
            .putBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, minimal)
            .putBoolean(AppSettings.KEY_RANK_THEME_WALLPAPER_ENABLED, !minimal)
            .commit()

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id-ID"))
        AppCompatDelegate.setDefaultNightMode(
            if (themeMode == "light") AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES,
        )
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun readerJourneyFitsRenderedViewportAndCapturesEvidence() {
        val activity = instrumentation.startActivitySync(
            Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as StatsActivity
        try {
            waitForAccessibleContent(minTextNodes = 6)
            val evidence = inspectCurrentWindow(activity.resources.displayMetrics.widthPixels)
            assertNoHorizontalOverflow("Reader Journey", evidence)
            assertTrue(
                "Reader Journey title must be exposed to accessibility in Indonesian locale",
                evidence.labels.any { it.contains(activity.getString(R.string.reader_journey), ignoreCase = true) },
            )
            captureEvidence(
                fileStem = "reader-journey",
                evidence = evidence,
                extra = JSONObject()
                    .put("theme", themeMode)
                    .put("fontScale", activity.resources.configuration.fontScale)
                    .put("density", activity.resources.displayMetrics.density)
                    .put("widthPx", activity.resources.displayMetrics.widthPixels)
                    .put("heightPx", activity.resources.displayMetrics.heightPixels),
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun representativeGoldenThemesRenderForCurrentLightDarkOrOledVariant() = runBlocking {
        val dao = database.getReaderJourneyDao()
        dao.mergeChapterAward(
            ReaderJourneyChapterEntity(
                mangaId = 9_900_001L,
                chapterId = 1L,
                isNovel = false,
                readingUnits = 1,
                completionCount = 1,
                awardedXp = 1_000_000L,
                firstCompletedAt = 1L,
                lastCompletedAt = 1L,
            ),
        )
        dao.rebuildProfileFromLedger()

        val themes = listOf(
            RankThemeId.FIRST_PAGE,
            RankThemeId.NEON_ARCHIVE,
            RankThemeId.GOLDEN_MANUSCRIPT,
            RankThemeId.ETERNAL_LIBRARY,
        )
        for (theme in themes) {
            profileStore.updateCosmetics(
                ReaderJourneyCosmeticPolicy.equipFullSet(
                    loadout = ReaderJourneyCosmeticLoadout(),
                    theme = theme,
                    currentRank = ReaderRank.LEGEND,
                ),
            )
            SystemClock.sleep(180)

            val activity = instrumentation.startActivitySync(
                Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as StatsActivity
            try {
                waitForAccessibleContent(minTextNodes = 6)
                val evidence = inspectCurrentWindow(activity.resources.displayMetrics.widthPixels)
                assertNoHorizontalOverflow("Rank Theme ${theme.stableId}", evidence)
                captureEvidence(
                    fileStem = "theme-${theme.stableId.lowercase()}",
                    evidence = evidence,
                    extra = JSONObject()
                        .put("themeMode", themeMode)
                        .put("rankThemeId", theme.stableId)
                        .put("fontScale", activity.resources.configuration.fontScale),
                )
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test
    fun appearanceSettingsControlsFitLargeTextAndRemainReachable() {
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsActivity

        val expectedLabels = linkedSetOf(
            activity.getString(R.string.rank_theme_reduce_motion),
            activity.getString(R.string.rank_theme_reduce_glow),
            activity.getString(R.string.rank_theme_minimal_cosmetics),
            activity.getString(R.string.rank_theme_wallpaper),
        )
        val foundLabels = linkedSetOf<String>()
        val allOverflows = ArrayList<String>()
        var lastEvidence: WindowEvidence? = null

        try {
            instrumentation.runOnMainSync {
                activity.openFragment(
                    fragmentClass = AppearanceSettingsFragment::class.java,
                    args = null,
                    isFromRoot = false,
                )
            }
            waitForAccessibleContent(minTextNodes = 6)

            repeat(MAX_SETTINGS_SWIPES + 1) { pass ->
                val evidence = inspectCurrentWindow(activity.resources.displayMetrics.widthPixels)
                lastEvidence = evidence
                allOverflows += evidence.horizontalOverflows.map { "pass=$pass $it" }
                for (label in expectedLabels) {
                    if (evidence.labels.any { it.equals(label, ignoreCase = true) }) {
                        foundLabels += label
                    }
                }
                if (!foundLabels.containsAll(expectedLabels)) {
                    swipeSettingsUp(
                        activity.resources.displayMetrics.widthPixels,
                        activity.resources.displayMetrics.heightPixels,
                    )
                    SystemClock.sleep(350)
                    instrumentation.waitForIdleSync()
                }
            }

            assertTrue(
                "Appearance text/control semantics overflowed horizontally: $allOverflows",
                allOverflows.isEmpty(),
            )
            assertTrue(
                "Phase 10 Rank Theme controls are not all reachable. Missing: ${expectedLabels - foundLabels}",
                foundLabels.containsAll(expectedLabels),
            )

            captureEvidence(
                fileStem = "appearance-settings",
                evidence = checkNotNull(lastEvidence),
                extra = JSONObject()
                    .put("theme", themeMode)
                    .put("fontScale", activity.resources.configuration.fontScale)
                    .put("foundRankThemeControls", JSONArray(foundLabels.toList()))
                    .put("expectedRankThemeControls", JSONArray(expectedLabels.toList())),
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun swipeSettingsUp(width: Int, height: Int) {
        val x = width / 2
        val startY = (height * 0.78f).toInt()
        val endY = (height * 0.28f).toInt()
        instrumentation.uiAutomation
            .executeShellCommand("input swipe $x $startY $x $endY 280")
            .close()
    }

    private fun waitForAccessibleContent(minTextNodes: Int) {
        val deadline = SystemClock.elapsedRealtime() + ACCESSIBILITY_TIMEOUT_MS
        var count = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            count = root?.let(::collectVisibleLabels)?.size ?: 0
            if (count >= minTextNodes) return
            SystemClock.sleep(150)
        }
        assertTrue("Rendered window exposed only $count text/control accessibility nodes", count >= minTextNodes)
    }

    private fun inspectCurrentWindow(viewportWidth: Int): WindowEvidence {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow) {
            "No active accessibility window"
        }
        val labels = ArrayList<String>()
        val overflows = ArrayList<String>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val label = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return@walk
            labels += label
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (
                bounds.width() > 0 &&
                (bounds.left < -HORIZONTAL_TOLERANCE_PX || bounds.right > viewportWidth + HORIZONTAL_TOLERANCE_PX)
            ) {
                overflows += "$label @ [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] viewport=$viewportWidth"
            }
        }
        return WindowEvidence(labels = labels.distinct(), horizontalOverflows = overflows.distinct())
    }

    private fun assertNoHorizontalOverflow(surface: String, evidence: WindowEvidence) {
        assertTrue(
            "$surface has horizontally clipped accessibility text/controls: ${evidence.horizontalOverflows}",
            evidence.horizontalOverflows.isEmpty(),
        )
    }

    private fun collectVisibleLabels(root: AccessibilityNodeInfo): List<String> {
        val labels = ArrayList<String>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
        }
        return labels
    }

    private fun walk(node: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> Unit) {
        block(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> walk(child, block) }
        }
    }

    private fun captureEvidence(fileStem: String, evidence: WindowEvidence, extra: JSONObject) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val json = JSONObject()
            .put("scenario", scenario)
            .put("surface", fileStem)
            .put("labels", JSONArray(evidence.labels))
            .put("horizontalOverflows", JSONArray(evidence.horizontalOverflows))
            .put("extra", extra)
            .toString(2)

        replaceDownload("$fileStem.png", "image/png") { output ->
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        replaceDownload("$fileStem.json", "application/json") { output ->
            output.write(json.toByteArray())
        }
    }

    private fun replaceDownload(name: String, mimeType: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val relativePath = "Download/miyorare-phase10-rendered/$scenario/"
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, name),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri, "w").use { output -> write(checkNotNull(output)) }
    }

    private data class WindowEvidence(
        val labels: List<String>,
        val horizontalOverflows: List<String>,
    )

    private companion object {
        const val ARG_SCENARIO = "phase10_scenario"
        const val ARG_THEME = "phase10_theme"
        const val MAX_SETTINGS_SWIPES = 7
        const val ACCESSIBILITY_TIMEOUT_MS = 20_000L
        const val HORIZONTAL_TOLERANCE_PX = 3
    }
}
