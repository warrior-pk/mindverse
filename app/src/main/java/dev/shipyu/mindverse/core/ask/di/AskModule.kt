package dev.shipyu.mindverse.core.ask.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shipyu.mindverse.core.ask.AskService
import dev.shipyu.mindverse.core.ask.HybridRetriever
import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.embed.EmbeddingEngine
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

/** Ask wiring (ticket 07): retrieval + grounded streaming through the chokepoint. */
@Module
@InstallIn(SingletonComponent::class)
object AskModule {

    @Provides
    @Singleton
    fun hybridRetriever(
        db: MindverseDatabase,
        repository: MemoryRepository,
        embedding: EmbeddingEngine,
        clock: Clock,
        zone: ZoneId,
    ): HybridRetriever = HybridRetriever(db, repository, embedding, clock, zone)

    @Provides
    @Singleton
    fun askService(
        retriever: HybridRetriever,
        ai: AiGateway,
    ): AskService = AskService(retriever, ai)
}
