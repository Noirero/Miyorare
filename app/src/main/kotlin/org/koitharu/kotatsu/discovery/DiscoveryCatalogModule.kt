package org.koitharu.kotatsu.discovery

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.data.*
import org.koitharu.kotatsu.discovery.domain.CatalogSourceResolution
import org.koitharu.kotatsu.discovery.domain.CatalogSourceResolver
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiscoveryCatalogModule {
	@Provides
	fun sourceResolver(resolver: CatalogSourceResolver): CatalogSourceResolution = resolver
	@Provides @Singleton
	fun repository(http: CatalogHttp, @ApplicationContext context: Context): TrackerCatalogRepository = TrackerCatalogRepository(
		setOf(AniListCatalogProvider(http), MALCatalogProvider(http, context.getString(R.string.mal_clientId)), KitsuCatalogProvider(http)),
		FileCatalogSnapshotStore(File(context.cacheDir, "tracker-discovery-v1")),
	)
}
