package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

enum class MigrationSearchScope {
	ALL,
	STORE,
	UMA,
	GEKKOUSHI,
	MIYORARE,
}

enum class MigrationSourceOrigin {
	STORE,
	UMA,
	GEKKOUSHI,
	MIYORARE,
	OTHER,
}

internal fun MangaSource.migrationSourceOrigin(): MigrationSourceOrigin = when (val source = unwrap()) {
	is MihonMangaSource -> MigrationSourceOrigin.STORE
	is TsukiMangaSource -> when (source.plugin.provider) {
		TsukiPluginProvider.MIYORARE -> MigrationSourceOrigin.MIYORARE
		TsukiPluginProvider.UMA -> MigrationSourceOrigin.UMA
		TsukiPluginProvider.GEKKOUSHI -> MigrationSourceOrigin.GEKKOUSHI
		TsukiPluginProvider.CUSTOM -> MigrationSourceOrigin.OTHER
	}
	else -> MigrationSourceOrigin.OTHER
}

internal fun MigrationSearchScope.matches(origin: MigrationSourceOrigin): Boolean = when (this) {
	MigrationSearchScope.ALL -> true
	MigrationSearchScope.STORE -> origin == MigrationSourceOrigin.STORE
	MigrationSearchScope.UMA -> origin == MigrationSourceOrigin.UMA
	MigrationSearchScope.GEKKOUSHI -> origin == MigrationSourceOrigin.GEKKOUSHI
	MigrationSearchScope.MIYORARE -> origin == MigrationSourceOrigin.MIYORARE
}

internal fun MigrationSearchScope.matches(source: MangaSource): Boolean = matches(source.migrationSourceOrigin())

data class MigrationSourcePool(
	val allSources: List<MangaSource>,
	val providerFailureCount: Int = 0,
) {
	fun sources(scope: MigrationSearchScope): List<MangaSource> = allSources.filter(scope::matches)

	fun availability(): MigrationSearchAvailability = MigrationSearchAvailability(
		counts = MigrationSearchScope.entries.associateWith { scope -> sources(scope).size },
		providerFailureCount = providerFailureCount,
	)
}

data class MigrationSearchAvailability(
	val counts: Map<MigrationSearchScope, Int>,
	val providerFailureCount: Int = 0,
) {
	fun count(scope: MigrationSearchScope): Int = counts[scope] ?: 0
}

internal fun isMigrationScopeIncomplete(
	scopedSourceNames: Collection<String>,
	failedSourceNames: Set<String>,
	successfulSourceNames: Set<String>,
): Boolean = scopedSourceNames.isEmpty() || (
	successfulSourceNames.isEmpty() && scopedSourceNames.all { it in failedSourceNames }
)

internal data class MigrationProviderMerge<T>(
	val values: List<T>,
	val failureCount: Int,
)

internal fun <T> mergeMigrationProviderResults(results: List<Result<List<T>>>): MigrationProviderMerge<T> =
	MigrationProviderMerge(
		values = results.flatMap { it.getOrDefault(emptyList()) },
		failureCount = results.count { it.isFailure },
	)
