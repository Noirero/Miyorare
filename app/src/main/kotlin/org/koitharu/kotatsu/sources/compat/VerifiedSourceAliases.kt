package org.koitharu.kotatsu.sources.compat

/**
 * Explicitly verified equivalences between existing provider identities and one official Miyorare
 * source. Entries are intentionally small and static: adding one requires proving the source id and
 * website domain against pinned upstream snapshots in the M2 CI intake check.
 *
 * Display names are never used for identity matching. [canonicalDisplayName] is presentation/path
 * metadata only, after the provider identity itself has already matched this verified entry.
 */
internal data class VerifiedSourceAlias(
    val canonicalId: CanonicalSourceId,
    val canonicalDisplayName: String,
    val language: String,
    val verifiedDomain: String,
    val mihonSourceId: Long,
    val officialPluginId: String,
    val sourceName: String,
) {
    val officialStoredName: String
        get() = "TSUKI:MIYORARE:$officialPluginId:$sourceName"

    val umaStoredName: String
        get() = "TSUKI:UMA:uma:$sourceName"
}

internal object VerifiedSourceAliases {

    val entries: List<VerifiedSourceAlias> = listOf(
        alias(
            pluginId = "miyorare-id",
            sourceName = "BACAMI",
            displayName = "Bacami",
            language = "id",
            domain = "v1.bacami.site",
            mihonSourceId = 2677079941490683989L,
        ),
        alias(
            pluginId = "miyorare-id",
            sourceName = "KIRYUU",
            displayName = "Kiryuu",
            language = "id",
            domain = "v7.kiryuu.to",
            mihonSourceId = 3639673976007021338L,
        ),
        alias(
            pluginId = "miyorare-id",
            sourceName = "KOMIKU",
            displayName = "Komiku",
            language = "id",
            domain = "komiku.org",
            mihonSourceId = 4838485846640015979L,
        ),
        alias(
            pluginId = "miyorare-en",
            sourceName = "ASURASCANS",
            displayName = "Asura Scans",
            language = "en",
            domain = "asurascans.com",
            mihonSourceId = 6247824327199706550L,
        ),
        alias(
            pluginId = "miyorare-en",
            sourceName = "AQUAMANGA",
            displayName = "Aqua Manga",
            language = "en",
            domain = "aquareader.org",
            mihonSourceId = 626267698662819838L,
        ),
        alias(
            pluginId = "miyorare-en",
            sourceName = "BATCAVE",
            displayName = "BatCave",
            language = "en",
            domain = "batcave.biz",
            mihonSourceId = 7422099479605463706L,
        ),
    )

    private val byCatalogueId: Map<Long, VerifiedSourceAlias> = entries.associateBy { it.mihonSourceId }

    private val byStoredName: Map<String, VerifiedSourceAlias> = entries
        .flatMap { entry ->
            listOf(
                entry.officialStoredName to entry,
                entry.umaStoredName to entry,
            )
        }
        .toMap()

    fun canonicalize(identity: CanonicalSourceIdentity): CanonicalSourceIdentity {
        val alias = when (identity.backend) {
            SourceBackend.MIHON, SourceBackend.KOTATSU ->
                identity.catalogueSourceId?.let(byCatalogueId::get)

            SourceBackend.TSUKI, SourceBackend.MIYORARE ->
                byStoredName[identity.storedName]

            SourceBackend.LNREADER, SourceBackend.INTERNAL -> null
        } ?: return identity

        return identity.copy(
            canonicalId = alias.canonicalId,
            // The official Miyorare key already owns this canonical id; every other provider is an alias.
            isAlias = identity.isAlias || identity.backend != SourceBackend.MIYORARE,
        )
    }

    fun findByCatalogueId(sourceId: Long): VerifiedSourceAlias? = byCatalogueId[sourceId]

    fun findByStoredName(storedName: String): VerifiedSourceAlias? = byStoredName[storedName]

    private fun alias(
        pluginId: String,
        sourceName: String,
        displayName: String,
        language: String,
        domain: String,
        mihonSourceId: Long,
    ): VerifiedSourceAlias = VerifiedSourceAlias(
        canonicalId = CanonicalSourceId("miyorare:$pluginId:$sourceName"),
        canonicalDisplayName = displayName,
        language = language,
        verifiedDomain = domain,
        mihonSourceId = mihonSourceId,
        officialPluginId = pluginId,
        sourceName = sourceName,
    )
}
