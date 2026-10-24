package dev.shipyu.mindverse.triggers.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.triggers.clipboard.ClipboardImporter
import dev.shipyu.mindverse.triggers.clipboard.ClipboardSettings
import dev.shipyu.mindverse.triggers.share.ShareIntentIngest
import java.time.Clock
import javax.inject.Singleton

/** M1 trigger wiring: share sheet, tile (no deps), clipboard auto-add (opt-in). */
@Module
@InstallIn(SingletonComponent::class)
object TriggersModule {

    @Provides
    @Singleton
    fun captureBlobStore(@ApplicationContext context: Context): CaptureBlobStore =
        CaptureBlobStore.forApp(context)

    @Provides
    @Singleton
    fun shareIntentIngest(
        @ApplicationContext context: Context,
        blobs: CaptureBlobStore,
        controller: PipelineController,
        clock: Clock,
    ): ShareIntentIngest = ShareIntentIngest(context, blobs, controller, clock)

    @Provides
    @Singleton
    fun clipboardSettings(@ApplicationContext context: Context): ClipboardSettings =
        ClipboardSettings(context)

    @Provides
    @Singleton
    fun clipboardImporter(
        @ApplicationContext context: Context,
        blobs: CaptureBlobStore,
        controller: PipelineController,
        clock: Clock,
    ): ClipboardImporter = ClipboardImporter(context, blobs, controller, clock)
}
