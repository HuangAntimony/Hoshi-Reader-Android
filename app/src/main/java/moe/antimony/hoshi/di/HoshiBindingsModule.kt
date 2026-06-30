package moe.antimony.hoshi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import moe.antimony.hoshi.features.anki.AndroidAnkiContentApi
import moe.antimony.hoshi.features.anki.AnkiBackend
import moe.antimony.hoshi.features.anki.AnkiContentApi
import moe.antimony.hoshi.features.anki.AnkiDroidBackendAdapter
import moe.antimony.hoshi.features.bookshelf.AndroidBookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.DictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionaryViewModelRepository
import moe.antimony.hoshi.features.statistics.AndroidStatisticsRepository
import moe.antimony.hoshi.features.statistics.StatisticsClock
import moe.antimony.hoshi.features.statistics.StatisticsRepository
import moe.antimony.hoshi.features.statistics.SystemStatisticsClock
import moe.antimony.hoshi.features.sync.DeviceCodeDriveAuthorizer
import moe.antimony.hoshi.features.sync.DriveAccessTokenProvider
import moe.antimony.hoshi.features.sync.DriveAuthorizer
import moe.antimony.hoshi.features.sync.DriveSyncDataSource
import moe.antimony.hoshi.features.sync.GoogleDriveClient
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.GitHubReleaseUpdateRepository
import moe.antimony.hoshi.features.update.ReleaseUpdateRepository
import moe.antimony.hoshi.features.update.UpdateDownloadController

@Module
@InstallIn(SingletonComponent::class)
internal interface HoshiBindingsModule {
    @Binds
    @Singleton
    fun bindDriveAuthorizer(authorizer: DeviceCodeDriveAuthorizer): DriveAuthorizer

    @Binds
    @Singleton
    fun bindDriveAccessTokenProvider(authorizer: DeviceCodeDriveAuthorizer): DriveAccessTokenProvider

    @Binds
    @Singleton
    fun bindDriveSyncDataSource(client: GoogleDriveClient): DriveSyncDataSource

    @Binds
    @Singleton
    fun bindBookshelfRepository(repository: AndroidBookshelfRepository): BookshelfRepository

    @Binds
    @Singleton
    fun bindDictionaryViewModelRepository(repository: AndroidDictionaryViewModelRepository): DictionaryViewModelRepository

    @Binds
    @Singleton
    fun bindDictionarySearchRepository(repository: AndroidDictionarySearchRepository): DictionarySearchRepository

    @Binds
    @Singleton
    fun bindStatisticsRepository(repository: AndroidStatisticsRepository): StatisticsRepository

    @Binds
    fun bindStatisticsClock(clock: SystemStatisticsClock): StatisticsClock

    @Binds
    @Singleton
    fun bindAnkiContentApi(api: AndroidAnkiContentApi): AnkiContentApi

    @Binds
    @Singleton
    fun bindAnkiBackend(backend: AnkiDroidBackendAdapter): AnkiBackend

    @Binds
    @Singleton
    fun bindUpdateDownloadController(manager: AndroidUpdateDownloadManager): UpdateDownloadController

    @Binds
    @Singleton
    fun bindReleaseUpdateRepository(repository: GitHubReleaseUpdateRepository): ReleaseUpdateRepository
}
