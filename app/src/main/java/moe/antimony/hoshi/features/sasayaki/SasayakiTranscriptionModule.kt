package moe.antimony.hoshi.features.sasayaki

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SasayakiTranscriptionModule {
    @Binds
    abstract fun repository(implementation: AndroidSasayakiTranscriptionRepository): SasayakiTranscriptionRepository

    companion object {
        @Provides
        fun clock(): SasayakiTranscriptionClock = SasayakiTranscriptionClock(System::nanoTime)
    }
}
