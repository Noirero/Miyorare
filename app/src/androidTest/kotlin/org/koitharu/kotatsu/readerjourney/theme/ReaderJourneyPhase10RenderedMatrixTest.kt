package org.koitharu.kotatsu.readerjourney.theme

import android.app.LocaleManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.LocaleList
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Inject
    lateinit var themeRuntime: ReaderJourneyThemeRuntime

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

        context.getSystemService(LocaleManager::class.java)
            .applicationLocales = LocaleList.forLanguageTags("id-ID")
    }

    @After
    fun tearDown() {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @Test
    fun readerJourneyFitsRenderedViewportAndCapturesEvidence() {
        val activity = instrumentation.startActivitySync(
            Intent(context, StatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as StatsActivity
        try {
            waitForAccessibleContent(minTextNodes = 6)
            assertEquals("id", activity.resources.configuration.locales[0].language)
            assertEquals("Perjalanan Pembaca", activity.getString(R.string.reader_journey))
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
            withTimeout(THEME_RUNTIME_TIMEOUT_MS) {
                themeRuntime.state.first { state ->
                    state.ledgerReady &&
                        state.lifetimeXp >= 1_000_000L &&
                        state.loadout.selectedThemeId == theme.stableId
                }
            }

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
    fun settingsSearchImeTransitionKeepsRenderedContentInsideViewport() {
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsActivity
        var searchView: SearchView? = null
        try {
            waitForAccessibleContent(minTextNodes = 6)
            instrumentation.runOnMainSync {
                val toolbar = checkNotNull(activity.findViewById<Toolbar>(R.id.toolbar))
                val searchItem = checkNotNull(toolbar.menu.findItem(R.id.action_search)) {
                    "Settings search menu item is missing"
                }
                check(searchItem.expandActionView()) { "Settings search action did not expand" }
                val expanded = checkNotNull(searchItem.actionView as? SearchView)
                expanded.isIconified = false
                val editText = checkNotNull(
                    expanded.findViewById<EditText>(androidx.appcompat.R.id.search_src_text),
                )
                expanded.requestFocus()
                editText.requestFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                searchView = expanded
            }

            waitForImeVisibility(activity, visible = true)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue("IME must contribute a positive bottom inset", imeInsets.bottom > 0)

            val evidence = inspectCurrentWindow(activity.resources.displayMetrics.widthPixels)
            assertNoHorizontalOverflow("Settings search with IME", evidence)
            captureEvidence(
                fileStem = "settings-ime",
                evidence = evidence,
                extra = JSONObject()
                    .put("theme", themeMode)
                    .put("fontScale", activity.resources.configuration.fontScale)
                    .put("imeBottomPx", imeInsets.bottom)
                    .put("systemBarsTopPx", systemBars.top)
                    .put("systemBarsBottomPx", systemBars.bottom),
            )
        } finally {
            instrumentation.runOnMainSync {
                val expanded = searchView
                val editText = expanded?.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
                if (editText != null) {
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(editText.windowToken, 0)
                }
                expanded?.clearFocus()
                activity.finish()
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
            assertEquals("id", activity.resources.configuration.locales[0].language)

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
                    scrollSettingsForward(
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

    private fun waitForImeVisibility(activity: SettingsActivity, visible: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + IME_TIMEOUT_MS
        var actual = false
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            actual = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (actual == visible) return
            SystemClock.sleep(120)
        }
        assertEquals("IME visibility did not reach requested state", visible, actual)
    }

    private fun scrollSettingsForward(width: Int, height: Int) {
        val root = findTargetApplicationRoot()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        if (root != null) pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(pending::addLast)
            }
        }

        // Coordinate fallback is retained only for unusual accessibility trees where the scroll
        // container does not expose ACTION_SCROLL_FORWARD.
        val x = width / 2
        val startY = (height * 0.82f).toInt()
        val endY = (height * 0.20f).toInt()
        instrumentation.uiAutomation
            .executeShellCommand("input swipe $x $startY $x $endY 320")
            .close()
    }

    private fun waitForAccessibleContent(minTextNodes: Int) {
        val deadline = SystemClock.elapsedRealtime() + ACCESSIBILITY_TIMEOUT_MS
        var count = 0
        var lastPackages = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val root = findTargetApplicationRoot()
            count = root?.let(::collectVisibleLabels)?.size ?: 0
            lastPackages = instrumentation.uiAutomation.windows
                .mapNotNull { it.root?.packageName?.toString() }
                .distinct()
            if (count >= minTextNodes) return
            SystemClock.sleep(150)
        }
        assertTrue(
            "Rendered Miyorare window exposed only $count text/control accessibility nodes; visiblePackages=$lastPackages",
            count >= minTextNodes,
        )
    }

    private fun inspectCurrentWindow(viewportWidth: Int): WindowEvidence {
        val root = checkNotNull(findTargetApplicationRoot()) {
            "No Miyorare application accessibility window; visiblePackages=" +
                instrumentation.uiAutomation.windows
                    .mapNotNull { it.root?.packageName?.toString() }
                    .distinct()
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

    private fun findTargetApplicationRoot(): AccessibilityNodeInfo? {
        val packageName = context.packageName
        val windows = instrumentation.uiAutomation.windows
        val applicationRoot = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == packageName }
        if (applicationRoot != null) return applicationRoot

        return instrumentation.uiAutomation.rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == packageName }
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
        const val MAX_SETTINGS_SWIPES = 18
        const val ACCESSIBILITY_TIMEOUT_MS = 20_000L
        const val THEME_RUNTIME_TIMEOUT_MS = 8_000L
        const val IME_TIMEOUT_MS = 8_000L
        const val HORIZONTAL_TOLERANCE_PX = 3
    }
}
