package org.koitharu.kotatsu.scrobbling.mangaupdates.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MangaUpdatesAuthActivityTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Before fun inject() { hilt.inject() }

	@Test fun passwordIsNotRestoredAndLoginRemainsProtectedAfterRecreation() {
		ActivityScenario.launch(MangaUpdatesAuthActivity::class.java).use { scenario ->
			scenario.onActivity { activity ->
				assertFalse(activity.viewBinding.editPassword.isSaveEnabled)
				assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
				assertTrue(runBlocking { activity.isPrivacySensitiveContent().first() })
				activity.viewBinding.editPassword.setText("fixture password that must not survive rotation")
				assertNull(activity.intent.data)
				assertFalse(activity.intent.toUri(Intent.URI_INTENT_SCHEME).contains("fixture password"))
			}
			scenario.recreate()
			scenario.onActivity { activity ->
				assertTrue(activity.viewBinding.editPassword.text.isNullOrEmpty())
				assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
			}
		}
	}

	@Test fun nativeLoginActivityIsNotExportedAndCancelDoesNotSubmitCredentials() {
		val app = InstrumentationRegistry.getInstrumentation().targetContext
		ActivityScenario.launch(MangaUpdatesAuthActivity::class.java).use { scenario ->
			scenario.onActivity { activity ->
				@Suppress("DEPRECATION")
				val info = app.packageManager.getActivityInfo(activity.componentName, PackageManager.GET_META_DATA)
				assertFalse(info.exported)
				activity.viewBinding.editPassword.setText("fixture-only")
				activity.viewBinding.buttonCancel.performClick()
				assertTrue(activity.isFinishing)
			}
		}
	}
}
