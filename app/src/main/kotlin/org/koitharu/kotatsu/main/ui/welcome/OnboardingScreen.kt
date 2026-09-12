package org.koitharu.kotatsu.main.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme

private const val PAGE_COUNT = 4
private val SCREEN_PADDING = 18.dp
private val HERO_SHAPE = RoundedCornerShape(30.dp)
private val CARD_SHAPE = RoundedCornerShape(24.dp)
private val INNER_SHAPE = RoundedCornerShape(18.dp)

private val OnboardingTop = Color(0xFF081426)
private val OnboardingBottom = Color(0xFF060D19)
private val Glass = Color(0xE616243B)
private val GlassSoft = Color(0xC9122036)
private val GlassStrong = Color(0xF01B2B48)
private val GlassStroke = Color.White.copy(alpha = 0.11f)
private val OnboardingText = Color(0xFFF7F9FF)
private val OnboardingMuted = Color(0xFFB8C4DA)
private val AccentBlue = Color(0xFF60A8FF)
private val AccentPurple = Color(0xFF9475FF)

data class OnboardingPermissions(
    val hasInstall: Boolean,
    val hasNotifications: Boolean,
    val hasBattery: Boolean,
)

data class OnboardingActions(
    val onThemeChange: (Int) -> Unit,
    val onColorSchemeChange: (String) -> Unit,
    val onAmoledChange: (Boolean) -> Unit,
    val onAmoledReset: () -> Unit,
    val onSelectDestination: () -> Unit,
    val onPermissionInstall: () -> Unit,
    val onPermissionNotifications: () -> Unit,
    val onPermissionBattery: () -> Unit,
    val onSignInGoogle: () -> Unit,
    val onRestoreDropSauce: () -> Unit,
    val onRestoreTachiyomi: () -> Unit,
    val onOpenGithub: () -> Unit,
    val onOpenDiscord: () -> Unit,
    val onVisitWebsite: () -> Unit,
    val onFinish: () -> Unit,
)

@Composable
fun OnboardingScreen(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    storageSummary: String?,
    isLoading: Boolean,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage by remember {
        derivedStateOf { pagerState.currentPage == PAGE_COUNT - 1 }
    }
    val backEnabled by remember {
        derivedStateOf { pagerState.currentPage > 0 }
    }

    BackHandler(enabled = backEnabled) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(OnboardingTop, Color(0xFF0A1730), OnboardingBottom),
                ),
            ),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 82.dp),
        ) { pageIndex ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = SCREEN_PADDING,
                        end = SCREEN_PADDING,
                        top = 6.dp,
                        bottom = 28.dp,
                    ),
            ) {
                OnboardingTopBar(
                    pageIndex = pageIndex,
                    onBack = {
                        scope.launch {
                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                        }
                    },
                    onSkip = actions.onFinish,
                )
                Spacer(Modifier.height(8.dp))

                when (pageIndex) {
                    0 -> WelcomeSlide(
                        selectedTheme = selectedTheme,
                        selectedColorScheme = selectedColorScheme,
                        isAmoledEnabled = isAmoledEnabled,
                        actions = actions,
                    )

                    1 -> StorageSlide(
                        storageSummary = storageSummary,
                        permissions = permissions,
                        actions = actions,
                    )

                    2 -> SyncSlide(
                        isLoading = isLoading,
                        actions = actions,
                    )

                    else -> FinishSlide(
                        selectedTheme = selectedTheme,
                        selectedColorScheme = selectedColorScheme,
                        storageSummary = storageSummary,
                        actions = actions,
                    )
                }
            }
        }

        OnboardingBottomBar(
            pageIndex = pagerState.currentPage,
            isLastPage = isLastPage,
            onNext = {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            onFinish = actions.onFinish,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun OnboardingTopBar(
    pageIndex: Int,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pageIndex == 0) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.88f),
            )
        } else {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(GlassSoft)
                    .clickable(onClick = onBack)
                    .padding(start = 10.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = OnboardingText,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.modern_onboarding_back),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = OnboardingText,
                )
            }
        }

        if (pageIndex < PAGE_COUNT - 1) {
            TextButton(onClick = onSkip) {
                Text(
                    text = stringResource(R.string.modern_onboarding_skip),
                    color = OnboardingText.copy(alpha = 0.86f),
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    pageIndex: Int,
    isLastPage: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, OnboardingBottom.copy(alpha = 0.94f)),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = 12.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val selected = index == pageIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (selected) 24.dp else 7.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "onboarding_dot_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = dotWidth, height = 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) AccentBlue
                                else Color.White.copy(alpha = 0.26f),
                            ),
                    )
                }
            }

            GradientActionButton(
                labelRes = if (isLastPage) R.string.done else R.string._continue,
                iconRes = if (isLastPage) R.drawable.ic_check else R.drawable.ic_arrow_forward,
                onClick = if (isLastPage) onFinish else onNext,
            )
        }
    }
}

@Composable
private fun GradientActionButton(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(AccentBlue, AccentPurple),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── Slide 0: Welcome / appearance ─────────────────────────────────────────────

@Composable
private fun WelcomeSlide(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    actions: OnboardingActions,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDarkEnabled = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemDark
    }

    LaunchedEffect(isDarkEnabled) {
        if (!isDarkEnabled && isAmoledEnabled) {
            actions.onAmoledReset()
        }
    }

    ArtworkHero(
        artRes = R.drawable.miyorare_favourites_violet,
        height = 285,
        iconRes = R.drawable.ic_welcome,
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = OnboardingText,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.modern_onboarding_welcome_description),
            style = MaterialTheme.typography.bodyMedium,
            color = OnboardingMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.modern_onboarding_welcome_quote),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.94f),
        )
        Spacer(Modifier.height(13.dp))
        BenefitChips()
    }

    Spacer(Modifier.height(16.dp))

    GlassSection(
        title = stringResource(R.string.modern_onboarding_preview_title),
    ) {
        LibraryPreview(
            selectedTheme = selectedTheme,
            selectedColorScheme = selectedColorScheme,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.color_theme),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnboardingText,
        )
        Spacer(Modifier.height(10.dp))
        ColorSchemeStrip(
            selectedColorScheme = selectedColorScheme,
            onColorSchemeChange = actions.onColorSchemeChange,
        )
    }

    Spacer(Modifier.height(12.dp))

    GlassSection(title = stringResource(R.string.theme)) {
        ThemeButtonGroup(
            selectedTheme = selectedTheme,
            onThemeChange = actions.onThemeChange,
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
        Spacer(Modifier.height(12.dp))
        OledRow(
            enabled = isDarkEnabled,
            checked = isAmoledEnabled && isDarkEnabled,
            onCheckedChange = actions.onAmoledChange,
        )
    }
}

@Composable
private fun ArtworkHero(
    @DrawableRes artRes: Int,
    height: Int,
    @DrawableRes iconRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(HERO_SHAPE)
            .background(GlassStrong),
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x22040A14),
                            Color(0x77050B17),
                            Color(0xF307101E),
                        ),
                    ),
                ),
        )
        Surface(
            modifier = Modifier
                .padding(18.dp)
                .size(48.dp)
                .align(Alignment.TopStart),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xB8182A49),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(12.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun BenefitChips() {
    val items = listOf(
        R.string.modern_onboarding_benefit_fast,
        R.string.modern_onboarding_benefit_light,
        R.string.modern_onboarding_benefit_flexible,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { labelRes ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                shape = CircleShape,
                color = Color(0x99182742),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = OnboardingText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryPreview(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
) {
    val themeNameRes = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> R.string.dark
        AppCompatDelegate.MODE_NIGHT_NO -> R.string.light
        else -> R.string.follow_system
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(INNER_SHAPE)
            .background(Color.White.copy(alpha = 0.055f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            schemeSwatch(selectedColorScheme),
                            AccentPurple.copy(alpha = 0.78f),
                        ),
                    ),
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_welcome),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(R.string.modern_onboarding_preview_library),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = OnboardingText,
            )
            Text(
                text = "${stringResource(selectedColorScheme.titleResId)} · ${stringResource(themeNameRes)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnboardingMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PreviewLine(0.90f, true)
            PreviewLine(0.63f, false)
        }
    }
}

@Composable
private fun PreviewLine(widthFraction: Float, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(if (emphasized) 7.dp else 5.dp)
            .clip(CircleShape)
            .background(
                if (emphasized) AccentBlue.copy(alpha = 0.68f)
                else Color.White.copy(alpha = 0.16f),
            ),
    )
}

@Composable
private fun ColorSchemeStrip(
    selectedColorScheme: ColorScheme,
    onColorSchemeChange: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = ColorScheme.getAvailableList(),
            key = { it.name },
        ) { scheme ->
            val selected = scheme == selectedColorScheme
            Surface(
                onClick = { onColorSchemeChange(scheme.name) },
                modifier = Modifier.width(82.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (selected) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.055f),
                border = BorderStroke(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) AccentBlue else GlassStroke,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        schemeSwatch(scheme).copy(alpha = 0.88f),
                                        Color(0xFF273755),
                                    ),
                                ),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .width(31.dp)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.72f)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(schemeSwatch(scheme)),
                        )
                        if (selected) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(18.dp),
                                shape = CircleShape,
                                color = AccentBlue,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.padding(3.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(scheme.titleResId),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OnboardingText else OnboardingMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun schemeSwatch(scheme: ColorScheme): Color = when (scheme) {
    ColorScheme.DEFAULT -> Color(0xFF6D83A7)
    ColorScheme.EXPRESSIVE -> Color(0xFF6D79F7)
    ColorScheme.MIKU -> Color(0xFF3DB9B2)
    ColorScheme.RENA -> Color(0xFFF08C7E)
    ColorScheme.FROG -> Color(0xFF72B86C)
    ColorScheme.BLUEBERRY -> Color(0xFF5A84E8)
    ColorScheme.SAKURA -> Color(0xFFF08AB2)
    ColorScheme.MAMIMI -> Color(0xFF9B78D6)
    ColorScheme.KANADE -> Color(0xFF7F8EEB)
    ColorScheme.ITSUKA -> Color(0xFFF49A6C)
    ColorScheme.SHANA -> Color(0xFFE96767)
    ColorScheme.LIME -> Color(0xFF91C95C)
    ColorScheme.AMBER -> Color(0xFFEAB15A)
    ColorScheme.SKY -> Color(0xFF58BCEB)
}

@Composable
private fun ThemeButtonGroup(
    selectedTheme: Int,
    onThemeChange: (Int) -> Unit,
) {
    val items = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.follow_system,
        AppCompatDelegate.MODE_NIGHT_NO to R.string.light,
        AppCompatDelegate.MODE_NIGHT_YES to R.string.dark,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, (mode, labelRes) ->
            val selected = selectedTheme == mode
            val background by animateColorAsState(
                targetValue = if (selected) Color(0xFF274B7F) else Color.White.copy(alpha = 0.055f),
                label = "theme_option_$index",
            )
            Surface(
                onClick = { onThemeChange(mode) },
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(17.dp),
                color = background,
                border = BorderStroke(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) AccentBlue else GlassStroke,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else OnboardingMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OledRow(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_eye_off),
                contentDescription = null,
                tint = OnboardingMuted.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.onboarding_full_black_oled),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = OnboardingText.copy(alpha = alpha),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue,
                uncheckedThumbColor = Color(0xFF8795AD),
                uncheckedTrackColor = Color(0xFF283953),
                disabledCheckedTrackColor = AccentBlue.copy(alpha = 0.32f),
                disabledUncheckedTrackColor = Color(0xFF283953).copy(alpha = 0.45f),
            ),
        )
    }
}

// ── Slide 1: Storage & permissions ───────────────────────────────────────────

@Composable
private fun StorageSlide(
    storageSummary: String?,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    ArtworkHero(
        artRes = R.drawable.miyorare_favourites_cyan,
        height = 190,
        iconRes = R.drawable.ic_storage,
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_storage_quote),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnboardingText,
        )
    }

    Spacer(Modifier.height(16.dp))
    PageTitleBlock(
        titleRes = R.string.modern_onboarding_storage_title,
        descriptionRes = R.string.modern_onboarding_storage_description,
    )
    Spacer(Modifier.height(14.dp))

    Surface(
        shape = CARD_SHAPE,
        color = Glass,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(R.drawable.ic_storage)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.modern_onboarding_download_folder),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = OnboardingText,
                    )
                    Text(
                        text = storageSummary ?: stringResource(R.string.modern_onboarding_default_destination),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnboardingMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GlassActionButton(
                iconRes = R.drawable.ic_storage,
                labelRes = R.string.modern_onboarding_select_destination,
                enabled = true,
                onClick = actions.onSelectDestination,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Surface(
        shape = CARD_SHAPE,
        color = GlassSoft,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            PermissionRow(
                iconRes = R.drawable.ic_plug_large,
                titleRes = R.string.modern_onboarding_permission_install,
                badgeRes = R.string.modern_onboarding_badge_important,
                isGranted = permissions.hasInstall,
                onClick = actions.onPermissionInstall,
            )
            GlassDivider()
            PermissionRow(
                iconRes = R.drawable.ic_notification,
                titleRes = R.string.modern_onboarding_permission_notifications,
                badgeRes = R.string.modern_onboarding_badge_optional,
                isGranted = permissions.hasNotifications,
                onClick = actions.onPermissionNotifications,
            )
            GlassDivider()
            PermissionRow(
                iconRes = R.drawable.ic_battery_outline,
                titleRes = R.string.modern_onboarding_permission_battery,
                badgeRes = R.string.modern_onboarding_badge_recommended,
                isGranted = permissions.hasBattery,
                onClick = actions.onPermissionBattery,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    @StringRes badgeRes: Int,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(iconRes, active = isGranted)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = OnboardingText,
            )
            StatusBadge(
                labelRes = if (isGranted) R.string.modern_onboarding_badge_active else badgeRes,
                active = isGranted,
            )
        }
        Icon(
            painter = painterResource(
                if (isGranted) R.drawable.ic_check else R.drawable.ic_arrow_forward,
            ),
            contentDescription = null,
            tint = if (isGranted) AccentBlue else OnboardingMuted,
            modifier = Modifier.size(19.dp),
        )
    }
}

// ── Slide 2: Sync & restore ──────────────────────────────────────────────────

@Composable
private fun SyncSlide(
    isLoading: Boolean,
    actions: OnboardingActions,
) {
    ArtworkHero(
        artRes = R.drawable.miyorare_favourites_emerald,
        height = 210,
        iconRes = R.drawable.ic_sync,
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_sync_quote),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnboardingText,
        )
    }

    Spacer(Modifier.height(16.dp))
    PageTitleBlock(
        titleRes = R.string.modern_onboarding_sync_title,
        descriptionRes = R.string.modern_onboarding_sync_description,
    )
    Spacer(Modifier.height(14.dp))

    Surface(
        shape = CARD_SHAPE,
        color = Glass,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBadge(
                labelRes = R.string.modern_onboarding_badge_optional,
                active = false,
            )
            GlassActionButton(
                iconRes = R.drawable.ic_google_g,
                labelRes = R.string.modern_onboarding_google_title,
                enabled = !isLoading,
                emphasized = true,
                onClick = actions.onSignInGoogle,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Surface(
        shape = CARD_SHAPE,
        color = GlassSoft,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 7.dp)) {
            Text(
                text = stringResource(R.string.modern_onboarding_restore_hint),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OnboardingMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LinkActionRow(
                iconRes = R.drawable.ic_backup_restore,
                labelRes = R.string.modern_onboarding_restore_kotatsu,
                enabled = !isLoading,
                onClick = actions.onRestoreDropSauce,
            )
            GlassDivider()
            LinkActionRow(
                iconRes = R.drawable.ic_revert,
                labelRes = R.string.modern_onboarding_restore_tachi,
                enabled = !isLoading,
                onClick = actions.onRestoreTachiyomi,
            )
        }
    }
}

// ── Slide 3: Finish ───────────────────────────────────────────────────────────

@Composable
private fun FinishSlide(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    storageSummary: String?,
    actions: OnboardingActions,
) {
    val themeNameRes = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> R.string.dark
        AppCompatDelegate.MODE_NIGHT_NO -> R.string.light
        else -> R.string.follow_system
    }

    ArtworkHero(
        artRes = R.drawable.miyorare_favourites_sakura,
        height = 220,
        iconRes = R.drawable.ic_save_ok,
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_finish_quote),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnboardingText,
        )
    }

    Spacer(Modifier.height(16.dp))
    PageTitleBlock(
        titleRes = R.string.modern_onboarding_finish_title,
        descriptionRes = R.string.modern_onboarding_finish_description,
    )
    Spacer(Modifier.height(14.dp))

    FullWidthGradientButton(
        iconRes = R.drawable.ic_book_page,
        labelRes = R.string.modern_onboarding_start_reading,
        onClick = actions.onFinish,
    )

    Spacer(Modifier.height(12.dp))

    Surface(
        shape = CARD_SHAPE,
        color = Glass,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = stringResource(R.string.modern_onboarding_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = OnboardingText,
            )
            SummaryRow(
                labelRes = R.string.modern_onboarding_summary_theme,
                value = stringResource(themeNameRes),
            )
            SummaryRow(
                labelRes = R.string.modern_onboarding_summary_scheme,
                value = stringResource(selectedColorScheme.titleResId),
            )
            SummaryRow(
                labelRes = R.string.modern_onboarding_summary_folder,
                value = storageSummary ?: stringResource(R.string.modern_onboarding_default_destination),
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Surface(
        shape = CARD_SHAPE,
        color = GlassSoft,
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 7.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.modern_onboarding_community_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = OnboardingText,
                )
                Text(
                    text = stringResource(R.string.modern_onboarding_community_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnboardingMuted,
                )
            }
            LinkActionRow(
                iconRes = R.drawable.ic_github,
                labelRes = R.string.modern_onboarding_source_code,
                enabled = true,
                onClick = actions.onOpenGithub,
            )
            GlassDivider()
            LinkActionRow(
                iconRes = R.drawable.ic_discord,
                labelRes = R.string.modern_onboarding_discord,
                enabled = true,
                onClick = actions.onOpenDiscord,
            )
            GlassDivider()
            LinkActionRow(
                iconRes = R.drawable.ic_web,
                labelRes = R.string.modern_onboarding_website,
                enabled = true,
                onClick = actions.onVisitWebsite,
            )
        }
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

@Composable
private fun PageTitleBlock(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = OnboardingText,
        )
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = OnboardingMuted,
        )
    }
}

@Composable
private fun GlassSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = Glass,
        border = BorderStroke(1.dp, GlassStroke),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = OnboardingText,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun IconBubble(
    @DrawableRes iconRes: Int,
    active: Boolean = false,
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (active) Color(0xFF254E82) else Color.White.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, if (active) AccentBlue.copy(alpha = 0.50f) else GlassStroke),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (active) Color.White else OnboardingMuted,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun StatusBadge(
    @StringRes labelRes: Int,
    active: Boolean,
) {
    Surface(
        shape = CircleShape,
        color = if (active) Color(0xFF254E82) else Color.White.copy(alpha = 0.07f),
        border = BorderStroke(
            1.dp,
            if (active) AccentBlue.copy(alpha = 0.38f) else GlassStroke,
        ),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color.White else OnboardingMuted,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GlassDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = Color.White.copy(alpha = 0.08f),
    )
}

@Composable
private fun GlassActionButton(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    enabled: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (emphasized) {
        Brush.horizontalGradient(listOf(Color(0xFF315E9E), Color(0xFF443C88)))
    } else {
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.055f)))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) Color.White else OnboardingMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) OnboardingText else OnboardingMuted.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = if (enabled) OnboardingMuted else OnboardingMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FullWidthGradientButton(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(AccentPurple, AccentBlue),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(23.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LinkActionRow(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(iconRes)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) OnboardingText else OnboardingMuted.copy(alpha = 0.42f),
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = OnboardingMuted.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SummaryRow(
    @StringRes labelRes: Int,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = OnboardingMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = OnboardingText,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        )
    }
}
