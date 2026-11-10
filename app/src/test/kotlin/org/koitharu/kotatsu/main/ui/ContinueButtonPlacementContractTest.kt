package org.koitharu.kotatsu.main.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Guard the independent Continue button, not the five retained navigation destinations. */
class ContinueButtonPlacementContractTest {
    @Test fun `position preference defaults to right and is independent of nav slots`() {
        val settings = source("core/prefs/AppSettings.kt")
        val appearance = source("settings/AppearanceSettingsFragment.kt")
        val widget = source("core/ui/widgets/FloatingBottomNavigationView.kt")
        val navbar = source("main/ui/nav/FloatingNavBar.kt")
        assertTrue(settings.contains("get() = prefs.getString(KEY_NAV_CONTINUE_POSITION, \"RIGHT\") == \"LEFT\""))
        assertTrue(appearance.contains("rememberStringPref(AppSettings.KEY_NAV_CONTINUE_POSITION, \"RIGHT\")"))
        assertTrue(appearance.contains("entryValues = listOf(\"RIGHT\", \"LEFT\")"))
        assertTrue(widget.contains("private val continueOnLeftState = MutableStateFlow(false)"))
        assertTrue(widget.contains("continueOnLeft = continueOnLeft"))
        assertTrue(navbar.contains("if (continueOnLeft) continueAction()"))
        assertTrue(navbar.contains("if (!continueOnLeft) continueAction()"))
    }

    @Test fun `gear opens Settings directly and keeps search Back restoration`() {
        val main = source("main/ui/MainActivity.kt")
        assertTrue(main.contains("setOnClickListener { router.openSettings() }"))
        assertTrue(main.contains("contentDescription = getString(R.string.settings)"))
        assertTrue(main.contains("settings.observe(AppSettings.KEY_NAV_CONTINUE_POSITION)"))
        assertFalse(main.contains("MainUtilitiesSheet().show("))
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/kotlin/org/koitharu/kotatsu", relative),
        File("app/src/main/kotlin/org/koitharu/kotatsu", relative),
    ).first(File::isFile).readText()
}
