@file:OptIn(
	org.koitharu.kotatsu.parsers.InternalParsersApi::class,
	tsuki.InternalParsersApi::class,
)

package org.koitharu.kotatsu.tsuki.runtime

import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.ContentRating as KContentRating
import org.koitharu.kotatsu.parsers.model.ContentType as KContentType
import org.koitharu.kotatsu.parsers.model.Demographic as KDemographic
import org.koitharu.kotatsu.parsers.model.Manga as KManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as KMangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter as KMangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities as KMangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions as KMangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage as KMangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource as KMangaSource
import org.koitharu.kotatsu.parsers.model.MangaState as KMangaState
import org.koitharu.kotatsu.parsers.model.MangaTag as KMangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder as KSortOrder
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import tsuki.model.ContentRating as TContentRating
import tsuki.model.ContentType as TContentType
import tsuki.model.Demographic as TDemographic
import tsuki.model.Manga as TManga
import tsuki.model.MangaChapter as TMangaChapter
import tsuki.model.MangaListFilter as TMangaListFilter
import tsuki.model.MangaListFilterCapabilities as TMangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions as TMangaListFilterOptions
import tsuki.model.MangaPage as TMangaPage
import tsuki.model.MangaSource as TMangaSource
import tsuki.model.MangaState as TMangaState
import tsuki.model.MangaTag as TMangaTag
import tsuki.model.SortOrder as TSortOrder

internal fun TSortOrder.toMiyorare(): KSortOrder = KSortOrder.valueOf(name)
internal fun KSortOrder.toTsuki(): TSortOrder = TSortOrder.valueOf(name)

private fun TContentRating.toMiyorare(): KContentRating = KContentRating.valueOf(name)
private fun KContentRating.toTsuki(): TContentRating = TContentRating.valueOf(name)
private fun TContentType.toMiyorare(): KContentType = KContentType.entries.firstOrNull { it.name == name } ?: KContentType.OTHER
private fun KContentType.toTsuki(): TContentType = TContentType.entries.firstOrNull { it.name == name } ?: TContentType.OTHER
private fun TDemographic.toMiyorare(): KDemographic = KDemographic.entries.firstOrNull { it.name == name } ?: KDemographic.NONE
private fun KDemographic.toTsuki(): TDemographic = TDemographic.entries.firstOrNull { it.name == name } ?: TDemographic.NONE
private fun TMangaState?.toMiyorare(): KMangaState? = this?.let { KMangaState.valueOf(it.name) }
private fun KMangaState.toTsuki(): TMangaState = TMangaState.valueOf(name)

private fun TMangaTag.toMiyorare(source: KMangaSource) = KMangaTag(title, key, source)
private fun KMangaTag.toTsuki(source: TMangaSource) = TMangaTag(title, key, source)

internal fun TManga.toMiyorare(source: TsukiMangaSource) = KManga(
	id = id,
	title = title,
	altTitles = altTitles,
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.toMiyorare(),
	coverUrl = coverUrl,
	tags = tags.mapTo(mutableSetOf()) { it.toMiyorare(source) },
	state = state.toMiyorare(),
	authors = authors,
	largeCoverUrl = largeCoverUrl,
	description = description,
	chapters = chapters?.map { it.toMiyorare(source) },
	source = source,
)

internal fun TMangaChapter.toMiyorare(source: TsukiMangaSource) = KMangaChapter(
	id = id,
	title = title,
	number = number,
	volume = volume,
	url = url,
	scanlator = scanlator,
	uploadDate = uploadDate,
	branch = branch,
	source = source,
)

internal fun TMangaPage.toMiyorare(source: TsukiMangaSource) = KMangaPage(
	id = id,
	url = url,
	preview = preview,
	source = source,
)

internal fun KManga.toTsuki(source: TMangaSource) = TManga(
	id = id,
	title = title,
	altTitles = altTitles,
	url = url,
	publicUrl = publicUrl,
	rating = rating,
	contentRating = contentRating?.toTsuki(),
	coverUrl = coverUrl,
	tags = tags.mapTo(mutableSetOf()) { it.toTsuki(source) },
	state = state?.toTsuki(),
	authors = authors,
	largeCoverUrl = largeCoverUrl,
	description = description,
	chapters = chapters?.map { it.toTsuki(source) },
	source = source,
)

internal fun KMangaChapter.toTsuki(source: TMangaSource) = TMangaChapter(
	id = id,
	title = title,
	number = number,
	volume = volume,
	url = url,
	scanlator = scanlator,
	uploadDate = uploadDate,
	branch = branch,
	source = source,
)

internal fun KMangaPage.toTsuki(source: TMangaSource) = TMangaPage(
	id = id,
	url = url,
	preview = preview,
	source = source,
)

internal fun KMangaListFilter.toTsuki(source: TMangaSource) = TMangaListFilter(
	query = query,
	tags = tags.mapTo(mutableSetOf()) { it.toTsuki(source) },
	tagsExclude = tagsExclude.mapTo(mutableSetOf()) { it.toTsuki(source) },
	locale = locale,
	originalLocale = originalLocale,
	states = states.mapTo(mutableSetOf()) { it.toTsuki() },
	contentRating = contentRating.mapTo(mutableSetOf()) { it.toTsuki() },
	types = types.mapTo(mutableSetOf()) { it.toTsuki() },
	demographics = demographics.mapTo(mutableSetOf()) { it.toTsuki() },
	year = year,
	yearFrom = yearFrom,
	yearTo = yearTo,
	author = author,
	rawFilter = null,
)

internal fun TMangaListFilterCapabilities.toMiyorare() = KMangaListFilterCapabilities(
	isMultipleTagsSupported = isMultipleTagsSupported,
	isTagsExclusionSupported = isTagsExclusionSupported,
	isSearchSupported = isSearchSupported,
	isSearchWithFiltersSupported = isSearchWithFiltersSupported,
	isYearSupported = isYearSupported,
	isYearRangeSupported = isYearRangeSupported,
	isOriginalLocaleSupported = isOriginalLocaleSupported,
	isAuthorSearchSupported = isAuthorSearchSupported,
)

internal fun TMangaListFilterOptions.toMiyorare(source: TsukiMangaSource) = KMangaListFilterOptions(
	availableTags = availableTags.mapTo(mutableSetOf()) { it.toMiyorare(source) },
	availableStates = availableStates.mapTo(mutableSetOf()) { KMangaState.valueOf(it.name) },
	availableContentRating = availableContentRating.mapTo(mutableSetOf()) { it.toMiyorare() },
	availableContentTypes = availableContentTypes.mapTo(mutableSetOf()) { it.toMiyorare() },
	availableDemographics = availableDemographics.mapTo(mutableSetOf()) { it.toMiyorare() },
	availableLocales = availableLocales,
)

internal suspend inline fun <T> withTsukiExceptions(
	source: TsukiMangaSource,
	block: suspend () -> T,
): T {
	try {
		return block()
	} catch (e: tsuki.exception.AuthRequiredException) {
		throw AuthRequiredException(source, e)
	} catch (e: tsuki.exception.NotFoundException) {
		throw NotFoundException(e.message.orEmpty(), e.url)
	} catch (e: tsuki.exception.ContentUnavailableException) {
		throw ContentUnavailableException(e.message.orEmpty())
	} catch (e: tsuki.exception.ParseException) {
		throw ParseException(e.shortMessage, e.url, e)
	} catch (e: tsuki.exception.TooManyRequestExceptions) {
		throw TooManyRequestExceptions(e.url, e.getRetryDelay())
	}
}
