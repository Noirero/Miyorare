package org.koitharu.kotatsu.core.ui

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BaseActivityEntryPoint {

	val settings: AppSettings

	val screenshotPolicyHelper: ScreenshotPolicyHelper

	val exceptionResolverFactory: ExceptionResolver.Factory
}
