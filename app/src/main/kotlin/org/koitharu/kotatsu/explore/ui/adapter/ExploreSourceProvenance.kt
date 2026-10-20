package org.koitharu.kotatsu.explore.ui.adapter

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

enum class ExploreSourceFamily {
	MIYORARE_PACKS,
	EXTENSION_STORE,
	PLUGINS,
}

enum class ExploreSourceOrigin {
	MIYORARE_PACK,
	EXTENSION_STORE,
	UMA,
	GEKKOUSHI,
	LNREADER,
	CUSTOM_PLUGIN,
}

enum class ExploreSourceOriginFilter {
	ALL,
	MIYORARE_PACKS,
	EXTENSION_STORE,
	PLUGINS,
	UMA,
	GEKKOUSHI,
	LNREADER,
	CUSTOM_PLUGIN,
}

data class ExploreSourceFiltersItem(
	val origin: ExploreSourceOriginFilter,
	val language: String?,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is ExploreSourceFiltersItem
}

val ExploreSourceOrigin.family: ExploreSourceFamily
	get() = when (this) {
		ExploreSourceOrigin.MIYORARE_PACK -> ExploreSourceFamily.MIYORARE_PACKS
		ExploreSourceOrigin.EXTENSION_STORE -> ExploreSourceFamily.EXTENSION_STORE
		ExploreSourceOrigin.UMA,
		ExploreSourceOrigin.GEKKOUSHI,
		ExploreSourceOrigin.LNREADER,
		ExploreSourceOrigin.CUSTOM_PLUGIN,
		-> ExploreSourceFamily.PLUGINS
	}

fun ExploreSourceOrigin.matches(filter: ExploreSourceOriginFilter): Boolean = when (filter) {
	ExploreSourceOriginFilter.ALL -> true
	ExploreSourceOriginFilter.MIYORARE_PACKS -> family == ExploreSourceFamily.MIYORARE_PACKS
	ExploreSourceOriginFilter.EXTENSION_STORE -> family == ExploreSourceFamily.EXTENSION_STORE
	ExploreSourceOriginFilter.PLUGINS -> family == ExploreSourceFamily.PLUGINS
	ExploreSourceOriginFilter.UMA -> this == ExploreSourceOrigin.UMA
	ExploreSourceOriginFilter.GEKKOUSHI -> this == ExploreSourceOrigin.GEKKOUSHI
	ExploreSourceOriginFilter.LNREADER -> this == ExploreSourceOrigin.LNREADER
	ExploreSourceOriginFilter.CUSTOM_PLUGIN -> this == ExploreSourceOrigin.CUSTOM_PLUGIN
}

@StringRes
fun ExploreSourceFamily.titleRes(): Int = when (this) {
	ExploreSourceFamily.MIYORARE_PACKS -> R.string.explore_source_family_miyorare_packs
	ExploreSourceFamily.EXTENSION_STORE -> R.string.explore_source_family_extension_store
	ExploreSourceFamily.PLUGINS -> R.string.explore_source_family_plugins
}

@StringRes
fun ExploreSourceOrigin.titleRes(): Int = when (this) {
	ExploreSourceOrigin.MIYORARE_PACK -> R.string.explore_source_origin_miyorare_pack
	ExploreSourceOrigin.EXTENSION_STORE -> R.string.explore_source_origin_extension_store
	ExploreSourceOrigin.UMA -> R.string.explore_source_origin_uma
	ExploreSourceOrigin.GEKKOUSHI -> R.string.explore_source_origin_gekkoushi
	ExploreSourceOrigin.LNREADER -> R.string.explore_source_origin_lnreader
	ExploreSourceOrigin.CUSTOM_PLUGIN -> R.string.explore_source_origin_custom
}

@StringRes
fun ExploreSourceOriginFilter.titleRes(): Int = when (this) {
	ExploreSourceOriginFilter.ALL -> R.string.explore_content_filter_all
	ExploreSourceOriginFilter.MIYORARE_PACKS -> R.string.explore_source_family_miyorare_packs
	ExploreSourceOriginFilter.EXTENSION_STORE -> R.string.explore_source_family_extension_store
	ExploreSourceOriginFilter.PLUGINS -> R.string.explore_source_family_plugins
	ExploreSourceOriginFilter.UMA -> R.string.explore_source_origin_uma
	ExploreSourceOriginFilter.GEKKOUSHI -> R.string.explore_source_origin_gekkoushi
	ExploreSourceOriginFilter.LNREADER -> R.string.explore_source_origin_lnreader
	ExploreSourceOriginFilter.CUSTOM_PLUGIN -> R.string.explore_source_origin_custom
}

fun MangaSourceItem.exploreSourceOrigin(): ExploreSourceOrigin = source.mangaSource.exploreSourceOrigin()

internal fun MangaSource.exploreSourceOrigin(): ExploreSourceOrigin = when (val source = unwrap()) {
	is MihonMangaSource -> ExploreSourceOrigin.EXTENSION_STORE
	is LnMangaSource -> ExploreSourceOrigin.LNREADER
	is TsukiMangaSource -> source.plugin.provider.toExploreSourceOrigin()
	else -> ExploreSourceOrigin.CUSTOM_PLUGIN
}

internal fun TsukiPluginProvider.toExploreSourceOrigin(): ExploreSourceOrigin = when (this) {
	TsukiPluginProvider.MIYORARE -> ExploreSourceOrigin.MIYORARE_PACK
	TsukiPluginProvider.UMA -> ExploreSourceOrigin.UMA
	TsukiPluginProvider.GEKKOUSHI -> ExploreSourceOrigin.GEKKOUSHI
	TsukiPluginProvider.CUSTOM -> ExploreSourceOrigin.CUSTOM_PLUGIN
}
