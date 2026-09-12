package org.koitharu.kotatsu.sources.compat

import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves persisted source names to stable canonical identities without changing the persisted
 * value itself. This is intentionally metadata-only: resolution never loads an extension/plugin,
 * touches download files, or performs network work.
 *
 * Legacy Kotatsu parser names reuse the existing, domain-derived [KotatsuSourceMap]. A legacy name
 * and its Mihon/Keiyoushi target therefore receive the same `catalogue:<sourceId>` identity. Other
 * providers remain isolated until an explicit compatibility mapping exists; guessing from display
 * names is forbidden because unrelated sites can share the same title.
 */
@Singleton
class SourceAliasRegistry @Inject constructor(
	private val kotatsuSourceMap: KotatsuSourceMap,
) {

	suspend fun resolve(storedName: String): CanonicalSourceIdentity {
		val direct = StoredSourceIdentity.direct(storedName)
		if (direct.backend != SourceBackend.KOTATSU) return direct

		val target = kotatsuSourceMap.resolve(direct.storedName) ?: return direct
		return StoredSourceIdentity.mappedLegacy(
			storedName = direct.storedName,
			catalogueSourceId = target.sourceId,
			sourceName = target.sourceName,
			packageName = target.packageName,
		)
	}

	suspend fun canonicalId(storedName: String): CanonicalSourceId = resolve(storedName).canonicalId

	suspend fun areAliases(firstStoredName: String, secondStoredName: String): Boolean =
		canonicalId(firstStoredName) == canonicalId(secondStoredName)
}
