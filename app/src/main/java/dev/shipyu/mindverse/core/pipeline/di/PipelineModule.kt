package dev.shipyu.mindverse.core.pipeline.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.embed.EmbeddingEngine
import dev.shipyu.mindverse.core.embed.ModelRegistry
import dev.shipyu.mindverse.core.ocr.OcrEngine
import dev.shipyu.mindverse.core.pipeline.NotificationPipelineChip
import dev.shipyu.mindverse.core.pipeline.PipelineChip
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.core.pipeline.PipelineProcessor
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import dev.shipyu.mindverse.core.pipeline.work.WorkManagerPipelineScheduler
import java.time.Clock
import javax.inject.Singleton

/**
 * Pipeline wiring (ticket 04). The embedding engine resolves through [ModelRegistry] so
 * dropping in the MiniLM ONNX model later needs no call-site change; the chip is the
 * notification implementation; the scheduler is WorkManager.
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    @Provides
    @Singleton
    fun embeddingEngine(@ApplicationContext context: Context): EmbeddingEngine =
        ModelRegistry.select { path ->
            runCatching { context.assets.open(path).close() }.isSuccess
        }

    @Provides
    @Singleton
    fun pipelineChip(@ApplicationContext context: Context): PipelineChip =
        NotificationPipelineChip(context)

    @Provides
    @Singleton
    fun pipelineProcessor(
        db: MindverseDatabase,
        repository: MemoryRepository,
        ai: AiGateway,
        embedding: EmbeddingEngine,
        chip: PipelineChip,
        clock: Clock,
        ocr: OcrEngine,
    ): PipelineProcessor = PipelineProcessor(db, repository, ai, embedding, chip, clock, ocr)

    @Provides
    @Singleton
    fun pipelineScheduler(@ApplicationContext context: Context): PipelineScheduler =
        WorkManagerPipelineScheduler(context)

    @Provides
    @Singleton
    fun pipelineController(
        ingestion: CaptureIngestionService,
        scheduler: PipelineScheduler,
        db: MindverseDatabase,
        clock: Clock,
    ): PipelineController = PipelineController(ingestion, scheduler, db, clock)
}
