package dev.shipyu.mindverse.ui.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import dev.shipyu.mindverse.ui.captures.CaptureReviewController
import dev.shipyu.mindverse.ui.detail.MemoryDetailController
import dev.shipyu.mindverse.ui.gallery.GalleryController
import java.time.Clock
import java.time.ZoneId
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * UI controllers (ticket 06). One `@Named("ui")` scope backs all controllers: they are
 * screen-lifetime holders fed by the DB, safe to keep process-wide.
 */
@Module
@InstallIn(SingletonComponent::class)
object UiModule {

    @Provides
    @Singleton
    @Named("ui")
    fun uiScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun galleryController(
        db: MindverseDatabase,
        repository: MemoryRepository,
        clock: Clock,
        zone: ZoneId,
        @Named("ui") scope: CoroutineScope,
        importer: dev.shipyu.mindverse.triggers.screenshot.ScreenshotImporter,
        watcherSettings: dev.shipyu.mindverse.triggers.screenshot.WatcherSettings,
    ): GalleryController = GalleryController(db, repository, clock, zone, scope, importer, watcherSettings)

    @Provides
    @Singleton
    fun memoryDetailController(
        db: MindverseDatabase,
        repository: MemoryRepository,
    ): MemoryDetailController = MemoryDetailController(db, repository)

    @Provides
    @Singleton
    fun captureReviewController(
        db: MindverseDatabase,
        repository: MemoryRepository,
        scheduler: PipelineScheduler,
        clock: Clock,
    ): CaptureReviewController = CaptureReviewController(db, repository, scheduler, clock)

    @Provides
    @Singleton
    fun askController(
        service: dev.shipyu.mindverse.core.ask.AskService,
        @Named("ui") scope: CoroutineScope,
    ): dev.shipyu.mindverse.ui.ask.AskController =
        dev.shipyu.mindverse.ui.ask.AskController(service, scope)
}
