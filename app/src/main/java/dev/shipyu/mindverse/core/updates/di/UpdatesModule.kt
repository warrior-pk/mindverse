package dev.shipyu.mindverse.core.updates.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.BuildConfig
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.data.settings.AppSettings
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import dev.shipyu.mindverse.core.updates.ReleaseUpdateChecker
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.firstrun.FirstRunController
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Ticket 08 wiring: the GitHub Releases update check (ticket 10 piggybacks the Config
 * fetch on this same client/path) and first-run onboarding.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdatesModule {

    @Provides
    @Singleton
    fun appSettings(@ApplicationContext context: Context): AppSettings = AppSettings(context)

    @Provides
    @Singleton
    fun releaseUpdateChecker(client: OkHttpClient): ReleaseUpdateChecker = ReleaseUpdateChecker(
        client = client,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentVersionName = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun updateController(
        checker: ReleaseUpdateChecker,
        appSettings: AppSettings,
        clock: Clock,
        @Named("ui") scope: CoroutineScope,
    ): UpdateController = UpdateController(checker, appSettings, clock, scope)

    @Provides
    @Singleton
    fun firstRunController(
        appSettings: AppSettings,
        providerSettings: ProviderSettingsStore,
        keyStore: EncryptedSettingsStore,
    ): FirstRunController = FirstRunController(appSettings, providerSettings, keyStore)
}
