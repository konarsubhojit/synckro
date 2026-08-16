package com.synckro.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.synckro.data.telemetry.FirebaseTelemetry
import com.synckro.domain.telemetry.NoOpTelemetry
import com.synckro.domain.telemetry.Telemetry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

/**
 * Provides the app-wide [Telemetry] singleton.
 *
 * Uses [FirebaseTelemetry] when a default [FirebaseApp] was actually
 * initialized (which only happens when `google-services.json` was present at
 * build time — see `app/build.gradle.kts`), otherwise falls back to
 * [NoOpTelemetry] so the rest of the app never has to null-check or branch on
 * whether Firebase is configured.
 *
 * The user's opt-out preference (see `SettingsRepository.crashReportingEnabled`
 * / `analyticsEnabled`) is applied on top of this at app startup by calling
 * [Telemetry.setCrashlyticsCollectionEnabled] / [Telemetry.setAnalyticsCollectionEnabled] —
 * it does not change which implementation is provided here.
 */
@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {
    @Provides
    @Singleton
    fun provideTelemetry(
        @ApplicationContext context: Context,
    ): Telemetry =
        runCatching {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseTelemetry(context)
            } else {
                NoOpTelemetry()
            }
        }.onFailure {
            Timber.w(it, "TelemetryModule: Firebase unavailable, falling back to NoOpTelemetry")
        }.getOrElse { NoOpTelemetry() }
}
