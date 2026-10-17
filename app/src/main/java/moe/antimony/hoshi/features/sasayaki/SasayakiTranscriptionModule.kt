package moe.antimony.hoshi.features.sasayaki

import javax.inject.Singleton
import moe.antimony.hoshi.features.sasayaki.transcription.NakuruTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
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

    @Binds
    @Singleton
    abstract fun backend(implementation: NakuruTranscriptionBackend): SasayakiTranscriptionBackend

    companion object {
        @Provides
        fun clock(): SasayakiTranscriptionClock = SasayakiTranscriptionClock(System::nanoTime)
    }
}
