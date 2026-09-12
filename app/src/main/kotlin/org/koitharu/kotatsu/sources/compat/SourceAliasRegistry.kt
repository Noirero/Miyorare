package org.koitharu.kotatsu.sources.compat

import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves persisted source names to stable canonical identities without changing the persisted
 * value itself. This is intentionally metadata-only: resolution never loads an extension/plugin,
 * touches download files, or performs network work.
 *
 * Legacy Kotatsu parser names reuse the existing, domain-derived [KotatsuSourceMap]. Verified
 * cross-provider aliases are then applied from [VerifiedSourceAliases]. Providers remain isolated
 * unless an explicit alias exists; guessing from display names is forbidden.
 */
@Singleton
class SourceAliasRegistry @Inject constructor(
    private val kotatsuSourceMap: KotatsuSourceMap,
) {

    suspend fun resolve(storedName: String): CanonicalSourceIdentity {
        val direct = StoredSourceIdentity.direct(storedName)
        if (direct.backend != SourceBackend.KOTATSU) {
            return VerifiedSourceAliases.canonicalize(direct)
        }

        val target = kotatsuSourceMap.resolve(direct.storedName) ?: return direct
        val mapped = StoredSourceIdentity.mappedLegacy(
            storedName = direct.storedName,
            catalogueSourceId = target.sourceId,
            sourceName = target.sourceName,
            packageName = target.packageName,
        )
        return VerifiedSourceAliases.canonicalize(mapped)
    }

    suspend fun canonicalId(storedName: String): CanonicalSourceId = resolve(storedName).canonicalId

    suspend fun areAliases(firstStoredName: String, secondStoredName: String): Boolean =
        canonicalId(firstStoredName) == canonicalId(secondStoredName)
}
