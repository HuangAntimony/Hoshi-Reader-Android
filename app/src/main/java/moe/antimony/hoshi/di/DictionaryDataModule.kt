package moe.antimony.hoshi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import moe.antimony.hoshi.dictionary.DictionaryNativeBridge
import moe.antimony.hoshi.dictionary.DictionaryRemoteDataSource
import moe.antimony.hoshi.dictionary.HoshiDictionaryNativeBridge
import moe.antimony.hoshi.dictionary.UrlDictionaryRemoteDataSource

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DictionaryDataModule {
    @Binds
    @Singleton
    abstract fun bindDictionaryNativeBridge(
        implementation: HoshiDictionaryNativeBridge,
    ): DictionaryNativeBridge

    @Binds
    @Singleton
    abstract fun bindDictionaryRemoteDataSource(
        implementation: UrlDictionaryRemoteDataSource,
    ): DictionaryRemoteDataSource
}
