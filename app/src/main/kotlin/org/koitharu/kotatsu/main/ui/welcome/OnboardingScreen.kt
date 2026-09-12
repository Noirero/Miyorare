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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.settings.compose.ColorSchemePickerRow

private const val PAGE_COUNT = 4
private val CARD_SHAPE = RoundedCornerShape(28.dp)
private val INNER_SHAPE = RoundedCornerShape(18.dp)
private val SCREEN_PADDING = 20.dp

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
    val isLastPage by remember { derivedStateOf { pagerState.currentPage == PAGE_COUNT - 1 } }
    val backEnabled by remember { derivedStateOf { pagerState.currentPage > 0 } }

    BackHandler(enabled = backEnabled) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
        ) { pageIndex ->
            val iconRes = when (pageIndex) {
                0 -> R.drawable.ic_welcome
                1 -> R.drawable.ic_storage
                2 -> R.drawable.ic_sync
                else -> R.drawable.ic_save_ok
            }
            val titleRes = when (pageIndex) {
                0 -> R.string.modern_onboarding_welcome_title
                1 -> R.string.modern_onboarding_storage_title
                2 -> R.string.modern_onboarding_sync_title
                else -> R.string.modern_onboarding_finish_title
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        top = 8.dp,
                        start = SCREEN_PADDING,
                        end = SCREEN_PADDING,
                        bottom = 28.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    if (pageIndex < PAGE_COUNT - 1) {
                        TextButton(
                            onClick = actions.onFinish,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Text(stringResource(R.string.modern_onboarding_skip))
                        }
                    }
                }

                ModernHero(iconRes)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val isSelected = index == pagerState.currentPage
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 26.dp else 8.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "dot_$index",
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                        },
                        label = "dot_color_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = dotWidth, height = 8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }

            if (isLastPage) {
                ExtendedFloatingActionButton(
                    onClick = actions.onFinish,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.modern_onboarding_start_reading),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                )
            } else {
                FloatingActionButton(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = stringResource(R.string.next),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernHero(@DrawableRes iconRes: Int) {
    Surface(
        modifier = Modifier.size(112.dp),
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(19.dp),
                )
            }
        }
    }
}

// ── Slide 0: Welcome ─────────────────────────────────────────────────────────

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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        BenefitChips()

        MiniAppPreview(
            selectedTheme = selectedTheme,
            selectedColorScheme = selectedColorScheme,
        )

        ColorSchemePickerRow(
            title = stringResource(R.string.color_theme),
            selectedValue = selectedColorScheme.name,
            onValueChange = actions.onColorSchemeChange,
        )

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.theme),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ThemeButtonGroup(
                    selectedTheme = selectedTheme,
                    onThemeChange = actions.onThemeChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))

                val contentAlpha = if (isDarkEnabled) 1f else 0.38f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = isDarkEnabled,
                            onClick = { actions.onAmoledChange(!isAmoledEnabled) },
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_eye_off),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.onboarding_full_black_oled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                    }
                    Switch(
                        checked = isAmoledEnabled && isDarkEnabled,
                        onCheckedChange = { if (isDarkEnabled) actions.onAmoledChange(it) },
                        enabled = isDarkEnabled,
                    )
                }
            }
        }
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
                    .height(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.70f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniAppPreview(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
) {
    val themeNameRes = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> R.string.dark
        AppCompatDelegate.MODE_NIGHT_NO -> R.string.light
        else -> R.string.follow_system
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.modern_onboarding_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${stringResource(selectedColorScheme.titleResId)} · ${stringResource(themeNameRes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_welcome),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = INNER_SHAPE,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.modern_onboarding_preview_library),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.modern_onboarding_preview_continue),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    PreviewLine(0.92f, emphasized = true)
                    PreviewLine(0.72f, emphasized = false)
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(widthFraction: Float, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(if (emphasized) 9.dp else 7.dp)
            .clip(CircleShape)
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                },
            ),
    )
}

@Composable
private fun ThemeButtonGroup(selectedTheme: Int, onThemeChange: (Int) -> Unit) {
    val items = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.follow_system,
        AppCompatDelegate.MODE_NIGHT_NO to R.string.light,
        AppCompatDelegate.MODE_NIGHT_YES to R.string.dark,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, (mode, labelRes) ->
            val isSelected = selectedTheme == mode
            val isFirst = index == 0
            val isLast = index == items.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (isFirst) 50.dp else 8.dp,
                bottomStart = if (isFirst) 50.dp else 8.dp,
                topEnd = if (isLast) 50.dp else 8.dp,
                bottomEnd = if (isLast) 50.dp else 8.dp,
            )
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                label = "theme_btn_bg_$index",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "theme_btn_fg_$index",
            )
            Surface(
                onClick = { onThemeChange(mode) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = shape,
                color = bgColor,
                contentColor = contentColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

// ── Slide 1: Storage & Permissions ───────────────────────────────────────────

@Composable
private fun StorageSlide(
    storageSummary: String?,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_storage_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.modern_onboarding_download_folder),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = storageSummary ?: stringResource(R.string.modern_onboarding_default_destination),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                ThemedActionButton(
                    iconRes = R.drawable.ic_storage,
                    labelRes = R.string.modern_onboarding_select_destination,
                    onClick = actions.onSelectDestination,
                )
            }
        }

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                PermissionRow(
                    iconRes = R.drawable.ic_plug_large,
                    titleRes = R.string.modern_onboarding_permission_install,
                    badgeRes = R.string.modern_onboarding_badge_important,
                    isGranted = permissions.hasInstall,
                    onClick = actions.onPermissionInstall,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                PermissionRow(
                    iconRes = R.drawable.ic_notification,
                    titleRes = R.string.modern_onboarding_permission_notifications,
                    badgeRes = R.string.modern_onboarding_badge_optional,
                    isGranted = permissions.hasNotifications,
                    onClick = actions.onPermissionNotifications,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (isGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(10.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
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
            tint = if (isGranted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StatusBadge(@StringRes labelRes: Int, active: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        },
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

// ── Slide 2: Sync & Restore ──────────────────────────────────────────────────

@Composable
private fun SyncSlide(isLoading: Boolean, actions: OnboardingActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_sync_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusBadge(
                    labelRes = R.string.modern_onboarding_badge_optional,
                    active = false,
                )
                ThemedActionButton(
                    iconRes = R.drawable.ic_google_g,
                    labelRes = R.string.modern_onboarding_google_title,
                    enabled = !isLoading,
                    onClick = actions.onSignInGoogle,
                )
            }
        }

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.modern_onboarding_restore_hint),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LinkActionRow(
                    iconRes = R.drawable.ic_backup_restore,
                    labelRes = R.string.modern_onboarding_restore_kotatsu,
                    enabled = !isLoading,
                    onClick = actions.onRestoreDropSauce,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                LinkActionRow(
                    iconRes = R.drawable.ic_revert,
                    labelRes = R.string.modern_onboarding_restore_tachi,
                    enabled = !isLoading,
                    onClick = actions.onRestoreTachiyomi,
                )
            }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.modern_onboarding_finish_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.modern_onboarding_summary_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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

        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.modern_onboarding_community_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.modern_onboarding_community_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinkActionRow(
                    iconRes = R.drawable.ic_github,
                    labelRes = R.string.modern_onboarding_source_code,
                    enabled = true,
                    onClick = actions.onOpenGithub,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                LinkActionRow(
                    iconRes = R.drawable.ic_discord,
                    labelRes = R.string.modern_onboarding_discord,
                    enabled = true,
                    onClick = actions.onOpenDiscord,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                LinkActionRow(
                    iconRes = R.drawable.ic_web,
                    labelRes = R.string.modern_onboarding_website,
                    enabled = true,
                    onClick = actions.onVisitWebsite,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(@StringRes labelRes: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

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
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ThemedActionButton(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            text = stringResource(labelRes),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
