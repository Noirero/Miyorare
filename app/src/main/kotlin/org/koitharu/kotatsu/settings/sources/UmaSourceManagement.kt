package org.koitharu.kotatsu.settings.sources

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.tsuki.runtime.TsukiSourceConfig
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Provider

internal fun canNavigateTsukiSource(
	plugin: TsukiPluginDescriptor,
	source: TsukiSourceDescriptor,
	allowBroken: Boolean = false,
): Boolean = plugin.state == TsukiPluginState.ENABLED &&
	plugin.compatibility == TsukiCompatibilityStatus.COMPATIBLE &&
	(allowBroken || !source.isBroken)

internal fun umaLanguageCode(locale: String): String = locale.trim().lowercase(Locale.ROOT)

internal fun matchesUmaLanguage(provider: TsukiPluginProvider, locale: String, selected: String?): Boolean =
	provider != TsukiPluginProvider.UMA || selected == null || umaLanguageCode(locale) == selected

@Composable
internal fun UmaLanguageFilter(
	languages: List<String>,
	selected: String?,
	showProvider: Boolean,
	onSelect: (String?) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
	val all = stringResource(R.string.all_languages)
	val unknown = stringResource(R.string.kotatsu_parsers_unknown_language)
	val labels = remember(languages, locale, unknown) {
		val collator = java.text.Collator.getInstance(locale)
		languages.map { code ->
			code to if (code.isBlank()) unknown else Locale.forLanguageTag(code.replace('_', '-'))
				.getDisplayName(locale).ifBlank { code.uppercase(Locale.ROOT) }
		}.sortedWith { left, right -> collator.compare(left.second, right.second) }
	}
	val label = labels.firstOrNull { it.first == selected }?.second ?: all
	Box {
		FilterChip(
			selected = selected != null,
			onClick = { expanded = true },
			label = { Text(if (showProvider) "UMA · $label" else label) },
			leadingIcon = { Icon(painterResource(R.drawable.ic_language), null, Modifier.size(18.dp)) },
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(text = { Text(all) }, onClick = { expanded = false; onSelect(null) })
			for ((code, name) in labels) {
				DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(code) })
			}
		}
	}
}

@Composable
internal fun UmaSourceRow(
	title: String,
	subtitle: String,
	checked: Boolean,
	switchEnabled: Boolean,
	onBrowse: (() -> Unit)?,
	onSettings: (() -> Unit)?,
	onCheckedChange: (Boolean) -> Unit,
	leading: @Composable () -> Unit,
) {
	val switchLabel = stringResource(R.string.kotatsu_source_enable_named, title)
	val haptic = rememberHapticEffect()
	SettingsItem(
		title = title,
		subtitle = subtitle,
		enabled = switchEnabled,
		onClick = onBrowse,
		leading = leading,
		trailing = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				IconButton(onClick = { onSettings?.invoke() }, enabled = onSettings != null) {
					Icon(
						painterResource(R.drawable.ic_more_vert),
						contentDescription = stringResource(R.string.kotatsu_source_settings_named, title),
					)
				}
				Switch(
					checked = checked,
					onCheckedChange = { value ->
						haptic(if (value) HapticEffect.TOGGLE_ON else HapticEffect.TOGGLE_OFF)
						onCheckedChange(value)
					},
					enabled = switchEnabled,
					modifier = Modifier.semantics { contentDescription = switchLabel },
				)
			}
		},
	)
}

/**
 * Metadata-only resolution: no plugin class loader, parser handle or mutable registry dependency.
 * The installed descriptor SHA binds the bundled default to the exact verified release artifact.
 * Native overrides are read from the same preferences as Tsuki, never written by icon loading.
 */
internal class UmaWebsiteIconResolver(
	private val plugins: StateFlow<List<TsukiPluginDescriptor>>,
	private val context: Context,
	private val isNsfwDisabled: () -> Boolean,
) {
	private val permits = Semaphore(2)

	private fun currentSource(requested: TsukiMangaSource): TsukiMangaSource? {
		val plugin = plugins.value.firstOrNull {
			it.provider == TsukiPluginProvider.UMA &&
				it.pluginId == requested.pluginId &&
				it.sha256.equals(requested.plugin.sha256, ignoreCase = true) &&
				it.fileSize == requested.plugin.fileSize &&
				it.lastModified == requested.plugin.lastModified &&
				it.version == requested.plugin.version
		} ?: return null
		val source = plugin.sources.firstOrNull { it.name == requested.descriptor.name }
			?.takeIf { canNavigateTsukiSource(plugin, it) } ?: return null
		return TsukiMangaSource(plugin, source).takeUnless { isNsfwDisabled() && it.isNsfw() }
	}

	suspend fun load(requested: TsukiMangaSource): String? = withContext(Dispatchers.IO) {
		if (requested.plugin.provider != TsukiPluginProvider.UMA || requested.diagnosticTest) return@withContext null
		permits.withPermit {
			val current = currentSource(requested) ?: return@withPermit null
			val url = try {
				val override = TsukiSourceConfig.readStoredDomainOverride(
					context, current.plugin.storageKey, current.descriptor.name,
				)
				// Do not contact the original site if an explicitly configured domain is
				// rejected by the stricter icon policy.
				val domain = override ?: UmaWebsiteMetadata.defaultDomain(current.plugin, current.descriptor.name)
				domain?.let(::umaWebsiteIconUrl)
			} catch (error: CancellationException) {
				throw error
			} catch (_: LinkageError) {
				null
			} catch (_: Exception) {
				null
			}
			currentCoroutineContext().ensureActive()
			val latest = currentSource(requested) ?: return@withPermit null
			if (latest.descriptor != current.descriptor) return@withPermit null
			url
		}
	}
}

// Same origin-only favicon path as Kotatsu. Require HTTPS and a website hostname.
internal fun umaWebsiteIconUrl(domain: String): String? {
	val url = kotatsuWebsiteIconUrl(domain)?.toHttpUrlOrNull() ?: return null
	val host = url.host
	if (!url.isHttps || !host.contains('.') || host.contains(':') ||
		host.all { it.isDigit() || it == '.' } ||
		host.endsWith(".local") || host.endsWith(".localhost")
	) return null
	return url.toString()
}

private const val MAX_ICON_BYTES = 512L * 1024L

/**
 * Only inherit the app's DNS and proxy routing (including proxy-only authentication).
 * No parser/app website cookies, headers, interceptors, website authentication, or redirects.
 */
@Composable
internal fun rememberUmaWebsiteImageLoader(baseClientProvider: Provider<OkHttpClient>): ImageLoader {
	val context = LocalContext.current.applicationContext
	val loader = remember(context, baseClientProvider) {
		val client = lazy {
			val base = baseClientProvider.get()
			OkHttpClient.Builder()
				.dns(base.dns)
				.proxySelector(base.proxySelector)
				.proxyAuthenticator(base.proxyAuthenticator)
				.cookieJar(CookieJar.NO_COOKIES)
				.authenticator(Authenticator.NONE)
				.followRedirects(false)
				.followSslRedirects(false)
				.callTimeout(8, TimeUnit.SECONDS)
				.dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
				.addInterceptor { chain ->
					val response = chain.proceed(chain.request())
					val body = response.body
					try {
						if (body != null && (body.contentLength() > MAX_ICON_BYTES ||
							body.source().request(MAX_ICON_BYTES + 1))) {
							throw IOException("UMA favicon exceeds size limit")
						}
						response
					} catch (error: Exception) {
						response.close()
						throw error
					}
				}
				.build()
		}
		ImageLoader.Builder(context)
			.fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(2))
			.decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
			.diskCache(null)
			.components { add(OkHttpNetworkFetcherFactory(callFactory = client::value)) }
			.build()
	}
	DisposableEffect(loader) { onDispose { loader.shutdown() } }
	return loader
}

@Composable
internal fun UmaWebsiteIcon(
	source: TsukiMangaSource,
	load: Boolean,
	imageLoader: ImageLoader?,
	loadWebsiteIcon: suspend (TsukiMangaSource) -> String?,
	iconSession: Int?,
) {
	key(source.name, source.plugin.sha256, source.plugin.fileSize, source.plugin.lastModified,
		source.plugin.version, load, iconSession) {
		val url by produceState<String?>(null) {
			value = if (load) loadWebsiteIcon(source) else null
		}
		val fallback = painterResource(R.drawable.ic_web)
		val modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
		if (imageLoader != null) {
			AsyncImage(
				model = url,
				imageLoader = imageLoader,
				contentDescription = null,
				placeholder = fallback,
				error = fallback,
				fallback = fallback,
				modifier = modifier,
			)
		} else {
			Icon(fallback, contentDescription = null, modifier = modifier)
		}
	}
}
