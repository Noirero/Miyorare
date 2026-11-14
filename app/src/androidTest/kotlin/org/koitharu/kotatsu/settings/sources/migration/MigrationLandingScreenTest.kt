package org.koitharu.kotatsu.settings.sources.migration

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.appbar.AppBarLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationLandingScreenTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()
	private val localeConfig = Configuration(context.resources.configuration).apply { setLocale(Locale.UK) }
	private val themedContext = ContextThemeWrapper(context.createConfigurationContext(localeConfig), R.style.Theme_Kotatsu)

	@After fun close() { loader.shutdown() }

	private fun manga(id: Long, sourceName: String) = Manga(
		id = id, title = "Story $id", altTitles = emptySet(), url = "/$id",
		publicUrl = "https://fixture.invalid/$id", rating = 0f,
		contentRating = null, coverUrl = null, tags = emptySet(),
		state = null, authors = emptySet(), source = MangaSource(sourceName),
	)

	private fun state() = BrokenSourcesMigrationState(
		isLoading = false,
		sources = listOf(
			LibrarySourceOption(
				key = "family:example", canonicalKey = "family:example",
				sourceKeys = setOf("SOURCE_EN", "SOURCE_JA"),
				title = "E-Hentai (EN)", mangaCount = 2, isUnavailable = true,
				iconSourceKey = "SOURCE_EN", iconUrl = null,
				variants = listOf(
					LibrarySourceVariant("SOURCE_EN", "E-Hentai (EN)", 1),
					LibrarySourceVariant("SOURCE_JA", "E-Hentai (JA)", 1),
				),
			),
		),
		manga = listOf(manga(1L, "SOURCE_EN"), manga(2L, "SOURCE_JA")),
	)

	@Test
	fun sourceFirstNavigationOpensASelectedManga() {
		var opened = emptyList<Long>()
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext, LocalConfiguration provides localeConfig) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader,
						onRetry = {}, onOpenManga = { ids, _ -> opened = ids.toList() },
					)
				}
			}
		}
		compose.onNodeWithText("Choose a source").assertExists()
		compose.onNodeWithText("E-Hentai (EN)").performClick()
		compose.onNodeWithText("Story 1").performClick()
		assertEquals(listOf(1L), opened)
	}

	@Test
	fun sourceAllAndSearchUseOnlyTheExactSourceIdentity() {
		var opened = emptyList<Long>()
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext, LocalConfiguration provides localeConfig) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader,
						onRetry = {}, onOpenManga = { ids, _ -> opened = ids.toList() },
					)
				}
			}
		}
		compose.onNodeWithText("E-Hentai (JA)").performClick()
		compose.onNodeWithText("Story 2").assertExists()
		compose.onNodeWithText("Story 1").assertDoesNotExist()
		compose.onNodeWithTag("migration-landing-search").performTextInput("Nothing")
		compose.onNodeWithText("Story 2").assertDoesNotExist()
		compose.onNodeWithText("Migrate all (1)").performClick()
		assertEquals(listOf(2L), opened)
	}

	@Test
	fun longPressTitleSelectsAndRoutesOnlySelectedMangaToBatch() {
		var opened = emptyList<Long>()
		var action: org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction? = null
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext, LocalConfiguration provides localeConfig) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader, onRetry = {},
						onOpenManga = { ids, preferred -> opened = ids.toList(); action = preferred },
					)
				}
			}
		}
		compose.onNodeWithText("E-Hentai (EN)").performClick()
		compose.onNodeWithText("Story 1").performTouchInput { longClick() }
		compose.onNodeWithText("1 selected").assertExists()
		compose.onNodeWithText("Copy (1)").performClick()
		assertEquals(listOf(1L), opened)
		assertEquals(org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction.COPY, action)
	}

	@Test
	fun coverOpensDetailsWithoutStartingMigration() {
		var openedDetails = 0L
		var migrated = 0
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext, LocalConfiguration provides localeConfig) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader, onRetry = {},
						onOpenManga = { _, _ -> migrated++ },
						onOpenDetails = { openedDetails = it.id },
					)
				}
			}
		}
		compose.onNodeWithText("E-Hentai (EN)").performClick()
		compose.onNodeWithTag("migration-landing-cover-1").performClick()
		assertEquals(1L, openedDetails)
		assertEquals(0, migrated)
	}
 
    @Test
    fun sourceBackAndToolbarUpUseSameNavigationAndFooterRemainsVisible() {
        var up: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides themedContext, LocalConfiguration provides localeConfig) {
                MaterialTheme {
                    BrokenSourcesMigrationScreen(
                        state = state(), imageLoader = loader, onRetry = {},
                        onOpenManga = { _, _ -> },
                        onSourceBackChanged = { up = it },
                    )
                }
            }
        }
        compose.onNodeWithText("E-Hentai (EN)").performClick()
        compose.onNodeWithTag("migration-landing-footer").assertIsDisplayed()
        compose.onNodeWithText("Story 1").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.runOnIdle { checkNotNull(up).invoke() }
        compose.onNodeWithText("Choose a source").assertExists()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, up) }
        compose.onNodeWithText("E-Hentai (EN)").performClick()
        compose.onNodeWithText("‹ Source").performClick()
        compose.onNodeWithText("Choose a source").assertExists()
    }
    @Test
    fun contentInsetRecomputesAfterAppBarFirstLayout() {
        lateinit var appBar: AppBarLayout
        lateinit var composeHost: View
        // A detached AppBarLayout has no real first-layout lifecycle. Attach both
        // views to the composition so the assertion observes the actual layout.
        compose.setContent {
            AndroidView(factory = { _ ->
                // Compose's test activity uses a bare theme; Material AppBarLayout
                // must use the same AppCompat-derived theme as the real settings UI.
                FrameLayout(themedContext).apply {
                    appBar = AppBarLayout(themedContext).apply {
                        addView(View(themedContext), AppBarLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 240,
                        ).apply {
                            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                        })
                    }
                    composeHost = View(themedContext)
                    addView(appBar, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    addView(composeHost, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                    bindMigrationContentInsets(composeHost, appBar)
                }
            })
        }
        // An AppBarLayout scroll range can be non-zero after measure, before its
        // layout callback has fired. Await real View layout rather than only Compose idleness.
        compose.waitUntil(timeoutMillis = 5_000) {
            appBar.isLaidOut && composeHost.isLaidOut
        }
        compose.runOnIdle {
            val scrollRange = appBar.totalScrollRange
            val footerPadding = composeHost.paddingBottom
            assertTrue("Fixture requires a collapsible app bar", scrollRange > 0)
            assertTrue(
                "Footer must reserve expanded toolbar overlap: " +
                    "padding=$footerPadding, range=$scrollRange, " +
                    "appBarLaidOut=${appBar.isLaidOut}, hostLaidOut=${composeHost.isLaidOut}",
                footerPadding >= scrollRange,
            )
        }
    }

}
