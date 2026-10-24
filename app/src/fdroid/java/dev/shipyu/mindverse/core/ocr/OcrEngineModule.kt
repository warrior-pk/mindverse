package dev.shipyu.mindverse.core.ocr

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrEngineModule {
    @Binds
    abstract fun bindOcrEngine(impl: FdroidOcrEngine): OcrEngine
}
