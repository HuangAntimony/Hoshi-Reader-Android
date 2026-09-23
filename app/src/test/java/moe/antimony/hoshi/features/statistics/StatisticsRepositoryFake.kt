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
    override suspend fun editSession(folder: String, id: String, characters: Int?, readingTime: Double?) {
        check(!failWrites)
        storedBook = storedBook?.let { book ->
            book.copy(sessions = book.sessions.mapValues { (key, change) ->
                if (key == id) change.replacing(change.value!!.copy(charactersRead = characters ?: change.value.charactersRead, readingTime = readingTime ?: change.value.readingTime)) else change
            })
        }
    }
    override suspend fun deleteSessions(folder: String, ids: Collection<String>) {
        check(!failWrites)
        storedBook = storedBook?.let { book ->
            book.copy(sessions = book.sessions.mapValues { (id, change) -> if (id in ids) change.replacing(null) else change })
        }
    }
}
