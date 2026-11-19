package org.koitharu.kotatsu.settings.fonts

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.fonts.FontPack
import org.koitharu.kotatsu.core.fonts.InstalledFontPack
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.googleSansRounded

@AndroidEntryPoint
class FontSettingsFragment : BaseComposeSettingsFragment(R.string.font_title) {

	private val viewModel: FontSettingsViewModel by viewModels()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				FontSettingsScreen(viewModel)
			}
		}
	}
}

@Composable
private fun FontSettingsScreen(viewModel: FontSettingsViewModel) {
    val catalog by viewModel.catalog.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val activePack by viewModel.activePack.collectAsState()
    val busy by viewModel.busyPackIds.collectAsState()
    val catalogLoaded by viewModel.catalogLoaded.collectAsState()
    val catalogLoading by viewModel.catalogLoading.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingRemoval by remember { mutableStateOf<InstalledFontPack?>(null) }

    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error.orEmpty())
            viewModel.consumeError()
        }
    }

    // Use the actual bundled variable font, even when another pack is active.
    val activeFamily = remember(activePack) {
        activePack?.let(viewModel::fontFamily) ?: googleSansRounded
    }
    val activeName = activePack?.pack?.name ?: stringResource(R.string.font_builtin_title)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "active-preview") {
                    ActiveFontPreview(name = activeName, family = activeFamily)
                }
                item(key = "installed-title") {
                    FontSectionTitle(stringResource(R.string.font_section_installed))
                }
                item(key = "builtin") {
                    InstalledFontCard(
                        name = stringResource(R.string.font_builtin_title),
                        subtitle = stringResource(R.string.font_builtin_summary),
                        family = googleSansRounded,
                        selected = activePack == null,
                        onSelect = { viewModel.activate(null) },
                    )
                }
                items(installed, key = { "installed-" + it.pack.id }) { pack ->
                    val family = remember(pack) { viewModel.fontFamily(pack) }
                    InstalledFontCard(
                        name = pack.pack.name,
                        subtitle = stringResource(R.string.font_installed),
                        family = family,
                        selected = activePack?.pack?.id == pack.pack.id,
                        onSelect = { viewModel.activate(pack.pack.id) },
                        onRemove = { pendingRemoval = pack },
                        removing = pack.pack.id in busy,
                    )
                }
                item(key = "catalog-title") {
                    FontSectionTitle(stringResource(R.string.font_section_catalog))
                }
                if (catalog.isEmpty()) {
                    item(key = "catalog-status") {
                        CatalogStatus(
                            loading = catalogLoading,
                            loaded = catalogLoaded,
                            onRetry = viewModel::refreshCatalog,
                        )
                    }
                }
                items(catalog, key = { "catalog-" + it.id }) { pack ->
                    val installedPack = installed.firstOrNull { it.pack.id == pack.id }
                    val family = installedPack?.let { remember(it) { viewModel.fontFamily(it) } }
                    CatalogFontCard(
                        pack = pack,
                        installed = installedPack != null,
                        family = family,
                        busy = pack.id in busy,
                        onInstall = { viewModel.install(pack) },
                    )
                }
            }
            SnackbarHost(hostState = snackbar)
        }
    }

    pendingRemoval?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.font_remove_title)) },
            text = { Text(stringResource(R.string.font_remove_confirmation, pack.pack.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    viewModel.uninstall(pack.pack.id)
                }) {
                    Text(
                        text = stringResource(R.string.font_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FontSectionTitle(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_font),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActiveFontPreview(name: String, family: FontFamily) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val gradient = Brush.linearGradient(
        listOf(
            lerp(colors.surfaceContainerHigh, colors.primaryContainer, 0.22f),
            lerp(colors.surfaceContainer, colors.primaryContainer, 0.46f),
            lerp(colors.surfaceContainerHigh, colors.tertiaryContainer, 0.26f),
        ),
    )
    Box(
        modifier = Modifier.fillMaxWidth().clip(shape).background(gradient)
            .border(BorderStroke(1.dp, colors.primary.copy(alpha = 0.28f)), shape),
    ) {
        // Quiet, theme-derived waves replace the fixed cyan decoration of the concept mockup.
        Canvas(modifier = Modifier.matchParentSize()) {
            val first = Path().apply {
                moveTo(size.width * 0.48f, size.height)
                cubicTo(size.width * 0.79f, size.height * 0.78f, size.width * 0.72f, size.height * 0.30f, size.width, size.height * 0.20f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(first, color = colors.primary.copy(alpha = 0.11f))
            val second = Path().apply {
                moveTo(size.width * 0.80f, size.height)
                cubicTo(size.width * 0.89f, size.height * 0.65f, size.width * 0.92f, size.height * 0.60f, size.width, size.height * 0.53f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(second, color = colors.tertiary.copy(alpha = 0.10f))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Aa", style = MaterialTheme.typography.titleLarge, color = colors.primary)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.font_section_in_use),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = colors.primary.copy(alpha = 0.14f)) {
                    Text(
                        text = stringResource(R.string.font_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Aa Bb Cc",
                    style = MaterialTheme.typography.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.font_preview_sentence),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
                    color = colors.onSurface,
                )
                Text(
                    text = "0123456789",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = family),
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstalledFontCard(
    name: String,
    subtitle: String,
    family: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removing: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        shape = shape,
        color = if (selected) lerp(colors.surfaceContainerHigh, colors.primaryContainer, 0.30f) else colors.surfaceContainer,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = if (selected) stringResource(R.string.font_active) else subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.font_preview_sentence),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                    color = colors.onSurface,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            when {
                selected -> Surface(shape = RoundedCornerShape(50), color = colors.primary) {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Text("✓", color = colors.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                onRemove != null -> IconButton(onClick = onRemove, enabled = !removing) {
                    if (removing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.font_delete),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFontCard(
    pack: FontPack,
    installed: Boolean,
    family: FontFamily?,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val info = remember(pack, ctx) {
        listOf(pack.author, pack.licenseName, Formatter.formatShortFileSize(ctx, pack.totalSizeBytes))
            .filter(String::isNotBlank).joinToString(" · ")
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.36f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Aa",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = family ?: googleSansRounded),
                        color = colors.onSurface,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.isNotEmpty()) {
                    Text(
                        text = info,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (pack.description.isNotEmpty()) {
                    Text(
                        text = pack.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                installed -> Surface(shape = RoundedCornerShape(50), color = colors.primary.copy(alpha = 0.12f)) {
                    Text(
                        text = stringResource(R.string.font_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                else -> Button(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                ) {
                    Text(stringResource(R.string.font_download), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun CatalogStatus(loading: Boolean, loaded: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.font_catalog_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    text = stringResource(if (loaded) R.string.font_catalog_empty else R.string.font_catalog_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.font_retry))
                }
            }
        }
    }
}
