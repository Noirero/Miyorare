package org.koitharu.kotatsu.tsuki

import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity

/** Physical ownership moves; the persisted VoraToon namespace does not. */
internal object VoraToonPackRouting {
	const val GLOBAL_SHARD_ID = "miyorare-global-first-party"
	const val GLOBAL_ASSET_NAME = "miyorare-global-first-party.jar"

	val legacy = TsukiSourceIdentity(TsukiPluginProvider.MIYORARE, MiyorareOfficialSourcePacks.ID_PLUGIN_ID, "VORATOON")
	val global = legacy.copy(pluginId = GLOBAL_SHARD_ID)

	fun persisted(identity: TsukiSourceIdentity): TsukiSourceIdentity =
		if (identity == global) legacy else identity

	fun <T : Any> resolve(identity: TsukiSourceIdentity, lookup: (TsukiSourceIdentity) -> T?): T? =
		if (identity == legacy || identity == global) lookup(global) ?: lookup(legacy) else lookup(identity)

	fun isShadowed(identity: TsukiSourceIdentity, hasUsableGlobal: Boolean): Boolean =
		hasUsableGlobal && identity == legacy
}
