package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import android.content.Context
import coil3.ImageLoader
import org.junit.Assert.assertTrue
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import kotlin.math.abs

/**
 * Screen-level regression: the bare Details LazyColumn is not a Material Surface.
 * Poison its inherited LocalContentColor to reproduce an incorrectly dark/light host foreground.
 * Assert rendered foreground pixels, not just the presence of text in semantics.
 */
internal object DetailsTrackerForegroundContrastFixture {
    private val service = ScrobblerService.ANILIST

    fun checkForeground(
        compose: ComposeContentTestRule,
        context: Context,
        loader: ImageLoader,
        dark: Boolean,
        amoled: Boolean,
        custom: Boolean,
    ) {
        val colors = miyorareThemeColors(
            preset = if (custom) MiyorareThemePreset.CUSTOM else MiyorareThemePreset.MIYORARE,
            customAccent = if (custom) "#FF008577" else MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = dark,
            amoled = amoled,
            effectLevel = VisualEffectLevel.LIGHT,
        )
        val scheme = colors.colorScheme
        val poison = if (dark) Color.Black else Color.White
        val person = TrackerPerson("person-1", "Staff contrast target", null, listOf("Story role"))
        val recommendation = TrackerRecommendation(
            TrackerTarget(service, "recommendation-1"),
            "Recommendation contrast target",
            null,
            TrackerRecommendationKind.RECOMMENDATION,
        )
        val provider = AssociatedTrackerDetails(
            service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED,
            characters = TrackerResult.Empty(),
            staff = TrackerResult.Success(listOf(person)),
            recommendations = TrackerResult.Success(listOf(recommendation)),
        )
        val details = MangaDetails(SampleData.mangaDetails, null, null, "Contrast fixture", true)
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalContentColor provides poison,
                    LocalMiyorareVisualPalette provides colors.visualPalette,
                ) {
                    Box(Modifier.fillMaxSize().background(scheme.background)) {
                        DetailsExpressiveScreen(
                            details = details,
                            note = null, tags = emptyList(),
                            historyInfo = HistoryInfo(0, -2, null, false, false, true, null),
                            chapters = emptyList(), chapterReleasePrediction = null,
                            isChapterFilterActive = false, isLoading = false,
                            favouriteCount = 0, favouriteLabel = null, scrobblings = emptyList(),
                            trackerPeople = DetailsPeopleUiState(providers = listOf(provider)),
                            trackerRecommendations = DetailsPeopleUiState(providers = listOf(provider)),
                            mangaUpdatesProgressFailed = false,
                            genreRecommendations = emptyList(), expandedRelated = DetailsRelatedUiState(),
                            relatedDiscoveryEnabled = false, localSize = 0L, indexedLocalBook = null,
                            sourceTitle = "Contrast source", imageLoader = loader,
                            coverUrl = null, backdropUrl = null, isBackdropEnabled = false,
                            backdropBlurAmount = 0, visualEffectLevel = VisualEffectLevel.LIGHT,
                            style = DetailsUiMode.COMPACT, topInset = 0.dp, bottomContentPadding = 0.dp,
                            onScroll = {}, actions = noopActions(),
                        )
                    }
                }
            }
        }
        scrollTo(compose, hasTestTag("tracker-people:ANILIST:staff"))
        assertTextHasColor(compose, "Staff contrast target", scheme.onSurface)
        assertTextHasColor(compose, "Story role", scheme.onSurfaceVariant)
        assertTextHasColor(compose, context.getString(R.string.tracker_people_staff, context.getString(service.titleResId)), scheme.onSurface)
        assertTextHasColor(compose, "S", scheme.onSurface) // initial inside the no-image card

        scrollTo(compose, hasTestTag("tracker-recommendations:ANILIST:RECOMMENDATION"))
        assertTextHasColor(compose, "Recommendation contrast target", scheme.onSurface)
        assertTextHasColor(compose, context.getString(R.string.tracker_recommendations, context.getString(service.titleResId)), scheme.onSurface)
        assertTextHasColor(compose, "R", scheme.onSurface) // recommendation placeholder initial
    }

    private fun scrollTo(compose: ComposeContentTestRule, target: SemanticsMatcher) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(target)
    }

    private fun assertTextHasColor(compose: ComposeContentTestRule, text: String, expected: Color) {
        val pixels = compose.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue("Expected semantic foreground for '" + text + "': " + expected,
            pixels.hasForeground(expected))
    }

    private fun PixelMap.hasForeground(color: Color): Boolean =
        (0 until height).any { y ->
            (0 until width).any { x ->
                val pixel = this[x, y]
                abs(pixel.red - color.red) < 0.035f &&
                    abs(pixel.green - color.green) < 0.035f &&
                    abs(pixel.blue - color.blue) < 0.035f
            }
        }

    private fun noopActions() = DetailsExpressiveActions(
        onTagClick = {}, onCoverClick = {}, onScrobblingMore = {}, onScrobblingCardClick = {},
        onFavoriteClick = {}, onFavoriteLongClick = {}, onTitleClick = {}, onAuthorClick = {},
        onRelatedMore = {}, onRelatedClick = {}, onRelatedMangaClick = {}, onRelatedKeywordMore = { _, _ -> },
        onRelatedDiscoveryRequested = {}, onSourceClick = {}, onLocalClick = {},
        onGenreRecommendationsVisibilityChanged = {}, onReadClick = {}, onIncognitoClick = {},
        onForgetHistoryClick = {}, onChaptersClick = {}, onChapterOptionsClick = {},
        onChapterOptionsSetDefaultClick = {}, onChapterOptionsResetClick = {},
        onChapterClick = {}, onChapterDownloadClick = {}, onChapterPersonalClick = {},
        onTrackerPeopleRequested = {}, onTrackerPeopleRetry = {}, onTrackerPeopleRefresh = {},
        onTrackerRecommendationsRequested = {}, onTrackerRecommendationsRetry = {},
        onTrackerRecommendationsRefresh = {}, onTrackerRecommendationClick = {},
        onTrackerRecommendationProvider = {}, onMangaUpdatesProgressRetry = {},
    )
}
