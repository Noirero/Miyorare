package org.koitharu.kotatsu.core.logs

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object VerboseDiagnosticsModule {

    @Provides
    @IntoSet
    fun provideVerboseDiagnosticsLifecycleCallbacks(
        diagnostics: VerbosePerformanceDiagnostics,
    ): Application.ActivityLifecycleCallbacks = diagnostics
}
