package org.koitharu.kotatsu.settings.sources

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import java.io.File
import java.util.UUID
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import coil3.ImageLoader
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource

class KotatsuSourceManagementScreenTest {

	@get:Rule val compose = createComposeRule()

	@Test
	fun disabledSourceCanBrowseWithoutEnablingAndRowActionsAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val source = KotatsuMangaSource("FIXTURE", "Fixture Shelf", "en", "MANGA")
		val actions = mutableListOf<String>()
		compose.setContent {
			MaterialTheme {
				KotatsuSourcesScreen(
					sources = listOf(source),
					enabledSourceKeys = emptySet(),
					nsfwDisabled = false,
					onSourceEnabled = { key, enabled -> actions += "switch:$key:$enabled" },
					onSourceOpen = { actions += "open:${it.sourceKey}" },
					onSourceSettings = { actions += "settings:${it.sourceKey}" },
					loadWebsiteIcon = { null },
				)
			}
		}

		val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, source.displayName))
		compose.onNodeWithText(source.displayName).performClick()
		toggle.assertIsOff()
		compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, source.displayName))
			.performClick()
		toggle.performClick()
		compose.runOnIdle {
			assertEquals(listOf("open:FIXTURE", "settings:FIXTURE", "switch:FIXTURE:true"), actions)
		}
	}

	@Test
	fun umaRowBrowseSettingsAndSwitchAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "UMA Fixture"
		val actions = mutableListOf<String>()
		compose.setContent {
			MaterialTheme {
				UmaSourceRow(
					title = title,
					subtitle = "EN · MANGA",
					checked = false,
					switchEnabled = true,
					onBrowse = { actions += "browse" },
					onSettings = { actions += "settings" },
					onCheckedChange = { actions += "switch:$it" },
					leading = {},
				)
			}
		}
		val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
		compose.onNodeWithText(title).performClick()
		toggle.assertIsOff()
		compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title)).performClick()
		toggle.performClick()
		compose.runOnIdle { assertEquals(listOf("browse", "settings", "switch:true"), actions) }
	}

	@Test
	fun packRowBrowseSettingsAndSwitchAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "Pack Fixture"
		val source = TsukiSourceDescriptor("PACK_FIXTURE", title, "en", "MANGA")
		val actions = mutableListOf<String>()
		val imageLoader = ImageLoader.Builder(context).build()
		try {
			compose.setContent {
				MaterialTheme {
					MiyorareSourceSwitchItem(
						title = title, subtitle = "EN · MANGA", checked = false, enabled = true,
						onCheckedChange = { actions += "switch:$it" },
						onRowClick = { actions += "browse" },
						onSettings = { actions += "settings" },
						source = source, imageLoader = imageLoader,
					)
				}
			}
			val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
			compose.onNodeWithText(title).performClick()
			toggle.assertIsOff()
			compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title)).performClick()
			toggle.performClick()
			compose.runOnIdle { assertEquals(listOf("browse", "settings", "switch:true"), actions) }
		} finally {
			imageLoader.shutdown()
		}
	}
	@Test
	fun brokenPackRowKeepsDiagnosticActionAndDisablesSettingsAndSwitch() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "Broken Pack Fixture"
		val source = TsukiSourceDescriptor("BROKEN_PACK", title, "en", "MANGA", isBroken = true)
		val actions = mutableListOf<String>()
		val imageLoader = ImageLoader.Builder(context).build()
		try {
			compose.setContent {
				MaterialTheme {
					MiyorareSourceSwitchItem(
						title = title, subtitle = "Unavailable", checked = false, enabled = false,
						onCheckedChange = { actions += "switch:$it" },
						onRowClick = { actions += "diagnostic" },
						onSettings = null, source = source, imageLoader = imageLoader,
					)
				}
			}
			val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
			val settings = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title))
			toggle.assertIsNotEnabled().assertIsOff()
			settings.assertIsNotEnabled()
			// Touch input must not invoke either disabled control or the diagnostic row.
			toggle.performTouchInput { click() }
			settings.performTouchInput { click() }
			compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
			compose.onNodeWithText(title).performClick()
			compose.runOnIdle { assertEquals(listOf("diagnostic"), actions) }
		} finally {
			imageLoader.shutdown()
		}
	}
	@Test
	fun umaIconMetadataDoesNotInitializeParserOrMutatePersistedPluginState() = runBlocking {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val fixtureId = "uma_icon_" + UUID.randomUUID()
		val root = File(context.cacheDir, fixtureId)
		val directory = File(root, "tsuki_plugins/uma__uma")
		assertTrue(directory.mkdirs())
		val jar = File(directory, "plugin.jar").apply { writeText("not executable: metadata-only fixture") }
		val manifest = File(directory, "plugin.json").apply {
			writeText(JSONObject()
				.put("pluginId", "uma").put("displayName", "UMA")
				.put("provider", "UMA").put("version", "2462a1f")
				.put("sha256", UmaWebsiteMetadata.JAR_SHA256)
				.put("fileSize", 1257110).put("lastModified", 1)
				.put("compatibility", "COMPATIBLE").put("state", "ENABLED")
				.put("enabledSources", JSONArray().put("BACAMI"))
				.put("sources", JSONArray().put(JSONObject().put("name", "BACAMI")
					.put("title", "Bacami").put("locale", "id").put("contentType", "MANGA"))
					.put(JSONObject().put("name", "KOMIKU").put("title", "Komiku")
						.put("locale", "id").put("contentType", "MANGA")))
				.toString())
		}
		val prefNames = mutableSetOf<String>()
		val isolated = object : ContextWrapper(context) {
			override fun getFilesDir(): File = root
			override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
				val scoped = "${fixtureId}_$name"
				prefNames += scoped
				return super.getSharedPreferences(scoped, mode)
			}
		}
		// The registry's constructor registers a static resolver. Restore it after the isolated fixture.
		val active = TsukiPluginManager::class.java.getDeclaredField("activeInstance").apply { isAccessible = true }
		val previous = active.get(null)
		try {
			val manager = TsukiPluginManager(isolated)
			manager.initialize()
			val initial = manager.plugins.value
			val plugin = initial.single()
			val manifestBytes = manifest.readBytes()
			val jarBytes = jar.readBytes()
			val bacami = TsukiMangaSource(plugin, plugin.sources.single { it.name == "BACAMI" })
			val komiku = TsukiMangaSource(plugin, plugin.sources.single { it.name == "KOMIKU" })
			val prefs = isolated.getSharedPreferences("tsuki_uma__uma_KOMIKU", Context.MODE_PRIVATE)
			assertTrue(prefs.edit().putString("domain", "mirror.example").commit())
			val prefSnapshot = prefs.all.toMap()
			val resolver = UmaWebsiteIconResolver(manager.plugins, isolated) { false }
			// A genuine registry and manifest, but no loadable JAR: URL resolution must never
			// instantiate a parser or repair/mark the plugin and its sources as broken.
			assertEquals("https://v1.bacami.site/favicon.ico", resolver.load(bacami))
			assertEquals("https://mirror.example/favicon.ico", resolver.load(komiku))
			assertEquals(initial, manager.plugins.value)
			assertEquals(setOf("BACAMI"), manager.plugins.value.single().enabledSourceNames)
			assertEquals(TsukiPluginState.ENABLED, manager.plugins.value.single().state)
			assertArrayEquals(manifestBytes, manifest.readBytes())
			assertArrayEquals(jarBytes, jar.readBytes())
			assertEquals(prefSnapshot, prefs.all)

			// Unknown generations, removal during resolution and optional metadata failures.
			val state = MutableStateFlow(initial)
			val unknown = plugin.copy(sha256 = "unknown")
			state.value = listOf(unknown)
			val unknownSource = TsukiMangaSource(unknown, bacami.descriptor)
			assertNull(UmaWebsiteIconResolver(state, isolated) { false }.load(unknownSource))
			assertNull(UmaWebsiteIconResolver(state, isolated) { false }.load(bacami))
			state.value = initial
			val removed = object : ContextWrapper(isolated) {
				override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
					state.value = emptyList()
					return super.getSharedPreferences(name, mode)
				}
			}
			assertNull(UmaWebsiteIconResolver(state, removed) { false }.load(bacami))
			state.value = initial
			val failing = object : ContextWrapper(isolated) {
				override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
					throw LinkageError("metadata fixture failure")
			}
			assertNull(UmaWebsiteIconResolver(state, failing) { false }.load(bacami))
			val cancelled = object : ContextWrapper(isolated) {
				override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
					throw CancellationException("fixture cancellation")
			}
			try {
				UmaWebsiteIconResolver(state, cancelled) { false }.load(bacami)
				error("Cancellation must propagate")
			} catch (_: CancellationException) {
				// Expected: optional favicon failures must not swallow cancellation.
			}
			assertEquals(initial, manager.plugins.value)
			assertArrayEquals(manifestBytes, manifest.readBytes())
			assertEquals(prefSnapshot, prefs.all)
		} finally {
			active.set(null, previous)
			prefNames.forEach(context::deleteSharedPreferences)
			root.deleteRecursively()
		}
	}
}
