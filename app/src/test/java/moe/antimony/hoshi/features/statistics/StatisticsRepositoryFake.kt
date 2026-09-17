package moe.antimony.hoshi.features.statistics

internal open class StatisticsRepositoryFake : StatisticsRepository {
    var storedBook: StatisticsBookRecords? = null
    var archiveCount = 0
    var failWrites = false
    override suspend fun loadSnapshot() = StatisticsSnapshot(emptyList(), emptyList())
    override suspend fun loadBookStatistics(folder: String) = storedBook
    override suspend fun loadArchiveSummary() = archiveCount
    override suspend fun clearArchive() {
        check(!failWrites)
        archiveCount = 0
    }
    override suspend fun updateDay(folder: String, dateKey: String, characters: Int, totalMinutes: Int) {
        check(!failWrites)
        storedBook = storedBook?.let { book ->
            book.copy(statistics = book.statistics.map {
                if (it.dateKey == dateKey) it.copy(charactersRead = characters, readingTime = totalMinutes * 60.0) else it
            })
        }
    }
    override suspend fun deleteDay(folder: String, dateKey: String) {
        check(!failWrites)
        storedBook = storedBook?.let { book ->
            book.copy(statistics = book.statistics.filterNot { it.dateKey == dateKey })
                .takeUnless { it.isArchived && it.statistics.isEmpty() }
        }
    }
    override suspend fun deleteAll(folder: String) {
        check(!failWrites)
        storedBook = storedBook?.copy(statistics = emptyList())?.takeUnless { it.isArchived }
    }
}
