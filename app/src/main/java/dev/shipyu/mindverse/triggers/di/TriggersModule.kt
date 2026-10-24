package dev.shipyu.mindverse.triggers.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.triggers.screenshot.ContentResolverScreenshotReader
import dev.shipyu.mindverse.triggers.screenshot.MediaStoreScreenshotSource
import dev.shipyu.mindverse.triggers.screenshot.NotificationScreenshotPrompt
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotIngestHandler
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotPrompt
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotReader
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotSource
import dev.shipyu.mindverse.triggers.screenshot.WatcherSettings
import dev.shipyu.mindverse.triggers.share.ShareIntentIngest
import java.time.Clock
import javax.inject.Singleton

/** M1 trigger wiring (ticket 05): share sheet, screenshot watcher, tile has no deps. */
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
    fun watcherSettings(@ApplicationContext context: Context): WatcherSettings =
        WatcherSettings(context)

    @Provides
    @Singleton
    fun screenshotSource(
        @ApplicationContext context: Context,
        clock: Clock,
    ): ScreenshotSource = MediaStoreScreenshotSource(context, clock)

    @Provides
    @Singleton
    fun screenshotReader(@ApplicationContext context: Context): ScreenshotReader =
        ContentResolverScreenshotReader(context)

    @Provides
    @Singleton
    fun screenshotPrompt(@ApplicationContext context: Context): ScreenshotPrompt =
        NotificationScreenshotPrompt(context)

    @Provides
    @Singleton
    fun screenshotIngestHandler(
        blobs: CaptureBlobStore,
        controller: PipelineController,
        source: ScreenshotSource,
        reader: ScreenshotReader,
        settings: WatcherSettings,
        prompt: ScreenshotPrompt,
        clock: Clock,
    ): ScreenshotIngestHandler = ScreenshotIngestHandler(
        blobs = blobs,
        controller = controller,
        source = source,
        reader = reader,
        settings = settings,
        prompt = prompt,
        clock = clock,
    )
}
