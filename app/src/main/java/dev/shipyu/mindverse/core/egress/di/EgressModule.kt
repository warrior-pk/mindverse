package dev.shipyu.mindverse.core.egress.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.ai.AiProvider
import dev.shipyu.mindverse.core.ai.AiTransport
import dev.shipyu.mindverse.core.ai.GeminiTransport
import dev.shipyu.mindverse.core.ai.OpenAiCompatibleTransport
import dev.shipyu.mindverse.core.ai.ProviderSettingsStore
import dev.shipyu.mindverse.core.data.db.EgressLogDao
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.egress.EgressAiGateway
import dev.shipyu.mindverse.core.egress.EgressToggles
import dev.shipyu.mindverse.privacy.PrivacyController
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Egress + AI provider wiring (ticket 03): the ONLY public AI surface is [AiGateway]
 * (the chokepoint); both transports and the gateway implementation are internal —
 * call sites cannot compile a direct provider path (ADR-0002 invariant).
 */
@Module
@InstallIn(SingletonComponent::class)
object EgressModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Long read timeout: SSE answer streams are idle-tolerant, and reasoning models
        // pause between deltas. No call timeout — streams may legitimately run minutes.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun aiGateway(
        providerSettings: ProviderSettingsStore,
        keyStore: EncryptedSettingsStore,
        toggles: EgressToggles,
        db: MindverseDatabase,
        clock: Clock,
        client: OkHttpClient,
    ): AiGateway = EgressAiGateway(
        transports = mapOf(
            AiProvider.GEMINI to GeminiTransport(client),
            AiProvider.OPENAI_COMPATIBLE to OpenAiCompatibleTransport(client),
        ),
        providerSettings = providerSettings,
        keyStore = keyStore,
        toggles = toggles,
        logDao = db.egressLogDao(),
        clock = clock,
    )

    @Provides
    @Singleton
    fun providerSettingsStore(@ApplicationContext context: Context): ProviderSettingsStore =
        ProviderSettingsStore.create(context)

    @Provides
    @Singleton
    fun egressToggles(@ApplicationContext context: Context): EgressToggles =
        EgressToggles.create(context)

    @Provides
    @Singleton
    fun egressLogDao(db: MindverseDatabase): EgressLogDao = db.egressLogDao()

    @Provides
    @Singleton
    fun displayZone(): ZoneId = ZoneId.systemDefault()

    @Provides
    @Singleton
    fun privacyController(
        toggles: EgressToggles,
        logDao: EgressLogDao,
        zone: ZoneId,
    ): PrivacyController = PrivacyController(toggles, logDao, zone)
}
