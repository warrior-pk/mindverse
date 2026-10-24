package dev.shipyu.mindverse.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.settings.EncryptedSettingsStore
import dev.shipyu.mindverse.core.data.settings.KeystoreKeyCipher
import dev.shipyu.mindverse.core.data.settings.KeyCipher
import java.time.Clock
import javax.inject.Singleton

/** Storage core DI: database, ingestion boundary, memory repository, settings store. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MindverseDatabase =
        MindverseDatabase.forDevice(context)

    @Provides
    @Singleton
    fun captureIngestionService(
        db: MindverseDatabase,
        clock: Clock,
    ): CaptureIngestionService = CaptureIngestionService(db, clock)

    @Provides
    @Singleton
    fun memoryRepository(
        db: MindverseDatabase,
        clock: Clock,
    ): MemoryRepository = MemoryRepository(db, clock)

    @Provides
    @Singleton
    fun keyCipher(): KeyCipher = KeystoreKeyCipher()

    @Provides
    @Singleton
    fun encryptedSettingsStore(
        @ApplicationContext context: Context,
        cipher: KeyCipher,
    ): EncryptedSettingsStore = EncryptedSettingsStore(context, cipher)
}
