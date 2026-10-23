package org.koitharu.kotatsu.sync.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserDriveAuthCallbackRoutingTest {

	private val context: Context = ApplicationProvider.getApplicationContext()

	@Test
	fun browserAuthCallbackResolvesOnlyToControlledEntryPoint() {
		val expected = ComponentName(context, BrowserDriveAuthCallbackActivity::class.java)
		val valid = callbackIntent("miyorare://google-drive-auth?transaction_id=${"t".repeat(48)}&code=${"c".repeat(43)}")
		val validMatches = context.packageManager.queryIntentActivities(valid, 0)
			.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

		assertTrue(validMatches.contains(expected))
		assertEquals(1, validMatches.count { it == expected })

		val wrongHost = callbackIntent("miyorare://google-drive-auth-evil?transaction_id=${"t".repeat(48)}&code=${"c".repeat(43)}")
		val wrongMatches = context.packageManager.queryIntentActivities(wrongHost, 0)
			.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
		assertFalse(wrongMatches.contains(expected))
	}

	private fun callbackIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
		addCategory(Intent.CATEGORY_DEFAULT)
		addCategory(Intent.CATEGORY_BROWSABLE)
	}
}
