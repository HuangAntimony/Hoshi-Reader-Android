package moe.antimony.hoshi.features.audio

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

data class LocalAudioImportProgress(val copiedBytes: Long, val totalBytes: Long?)
enum class LocalAudioImportMode { Copy, Link }
data class LocalAudioDatabaseInfo(
    val displayName: String,
    val sizeBytes: Long,
    val isExternal: Boolean,
    val generation: String,
)

/** Owns both import modes and the single reusable read-only database session. */
@Singleton
class LocalAudioRepository internal constructor(
    private val filesDir: File,
    stateDirectory: File,
    private val access: LocalAudioAccess,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(context.filesDir, context.filesDir.resolve("local-audio"), AndroidLocalAudioAccess(context.contentResolver), ioDispatcher)

    // Standalone private stores used by non-Android repository fixtures.
    constructor(filesDir: File) : this(filesDir, filesDir.resolve(".local-audio"), AndroidLocalAudioAccess(), Dispatchers.IO)

    private val store = LocalAudioBindingStore(stateDirectory)
    private val legacyJson = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var state: LocalAudioStoredState? = null
    private var session: LocalAudioDatabaseSession? = null
    private var sessionGeneration: String? = null
    /** Legacy private path; new copies have generation-specific names for atomic replacement. */
    val dbFile: File get() = filesDir.resolve(AudioSettings.LocalAudioPath)

    suspend fun databaseInfo(): LocalAudioDatabaseInfo? = locked { readState().active?.info() }

    suspend fun hasRetainedPrivateDatabase(): Boolean = locked {
        readState().retainedPrivate?.let { privateFile(it).isFile } == true
    }

    suspend fun importDatabase(
        uri: Uri,
        mode: LocalAudioImportMode,
        onProgress: (LocalAudioImportProgress) -> Unit = {},
    ): LocalAudioDatabaseInfo = importDatabase(uri.toString(), mode, onProgress)

    internal suspend fun importDatabase(
        uri: String,
        mode: LocalAudioImportMode,
        onProgress: (LocalAudioImportProgress) -> Unit = {},
    ): LocalAudioDatabaseInfo = locked {
        val old = readState()
        val name = access.validateDocument(uri).ifBlank { "android.db" }
        val generation = UUID.randomUUID().toString()
        when (mode) {
            LocalAudioImportMode.Link -> linkDatabase(old, uri, name, generation)
            LocalAudioImportMode.Copy -> copyDatabase(old, uri, name, generation, onProgress)
        }
    }

    private suspend fun linkDatabase(old: LocalAudioStoredState, uri: String, name: String, generation: String): LocalAudioDatabaseInfo {
        var acquired = false
        var candidate: LocalAudioDatabaseSession? = null
        var published = false
        try {
            acquired = access.takeReadPermission(uri)
            candidate = access.openDocument(uri)
            val binding = LocalAudioBinding(
                generation = generation, displayName = name, sizeBytes = candidate.sizeBytes,
                uri = uri, fingerprint = candidate.fingerprint,
                ownsGrant = acquired || (old.active?.uri == uri && old.active.ownsGrant),
                sources = LocalAudioSourceConfig.defaultFor(candidate.querySources().toSet()),
            )
            currentCoroutineContext().ensureActive()
            val retained = old.active?.takeIf { it.uri == null } ?: old.retainedPrivate
            // Once publishing starts, completion and resource handover survive UI cancellation.
            withContext(NonCancellable) {
                publish(old.copy(active = binding, retainedPrivate = retained))
                published = true
                closeSession()
                session = candidate
                sessionGeneration = generation
                candidate = null
                releaseOldGrant(old.active, binding)
            }
            return binding.info()
        } finally {
            candidate?.close()
            if (!published && acquired && old.active?.uri != uri) releaseGrantQuietly(uri)
        }
    }

    private suspend fun copyDatabase(
        old: LocalAudioStoredState,
        uri: String,
        name: String,
        generation: String,
        onProgress: (LocalAudioImportProgress) -> Unit,
    ): LocalAudioDatabaseInfo {
        val directory = filesDir.resolve("Audio").also { it.mkdirs() }
        val destination = directory.resolve("android-$generation.db")
        val staging = directory.resolve("android-$generation.db.tmp")
        var published = false
        try {
            val expected = access.documentSize(uri)
            var copied = 0L
            val buffer = ByteArray(1024 * 1024)
            access.openStream(uri).use { input ->
                FileOutputStream(staging).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(LocalAudioImportProgress(copied, expected))
                    }
                    output.fd.sync()
                }
            }
            if (expected != null && copied != expected) throw IOException("Incomplete audio database copy: copied $copied of $expected bytes.")
            val binding = access.openPrivate(staging).use { database ->
                LocalAudioBinding(
                    generation = generation, displayName = name, sizeBytes = database.sizeBytes,
                    privateFileName = destination.name, fingerprint = database.fingerprint,
                    sources = LocalAudioSourceConfig.defaultFor(database.querySources().toSet()),
                )
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                moveLocalAudioFile(staging, destination)
                val retained = old.active?.takeIf { it.uri == null } ?: old.retainedPrivate
                publish(old.copy(active = binding, retainedPrivate = retained))
                published = true
                closeSession()
                releaseOldGrant(old.active, binding)
                try { deleteRetainedPrivateLocked() } catch (error: Exception) { record(error) }
            }
            return binding.info()
        } finally {
            staging.delete()
            if (!published) destination.delete()
        }
    }

    suspend fun deleteDatabase(): Unit = locked {
        val current = readState()
        val active = current.active ?: return@locked
        withContext(NonCancellable) {
            // Retain ownership in persisted state until deletion has succeeded.
            val retained = active.takeIf { it.uri == null } ?: current.retainedPrivate
            publish(current.copy(active = null, retainedPrivate = retained))
            closeSession()
            releaseOldGrant(active, null)
            if (active.uri == null) deleteRetainedPrivateLocked()
        }
    }

    suspend fun deleteRetainedPrivateDatabase(): Unit = locked {
        withContext(NonCancellable) { deleteRetainedPrivateLocked() }
    }

    private fun deleteRetainedPrivateLocked() {
        val current = readState()
        val retained = current.retainedPrivate ?: return
        val file = privateFile(retained)
        if (file.exists() && !file.delete()) throw IOException("Unable to delete retained private audio database.")
        publish(current.copy(retainedPrivate = null))
        if (file == dbFile) filesDir.resolve(AudioSettings.LocalAudioSourceConfigPath).delete()
    }

    suspend fun ensureSourceConfig(reset: Boolean = false): LocalAudioSourceConfig = locked {
        val active = readState().active ?: return@locked LocalAudioSourceConfig()
        // Settings refresh reopens the document so revoked grants/removal are visible promptly.
        if (active.uri != null) closeSession()
        val database = database(active)
        if (!reset && active.sources.sourceOrder.isNotEmpty()) return@locked active.sources
        val available = database.querySources().toSet()
        val config = if (reset) LocalAudioSourceConfig.defaultFor(available) else active.sources.repair(available)
        updateBinding(active.copy(sources = config, fingerprint = database.fingerprint))
        config
    }

    suspend fun updateSourceOrder(sourceOrder: List<String>): LocalAudioSourceConfig = locked {
        val active = readState().active ?: return@locked LocalAudioSourceConfig()
        val next = active.sources.copy(sourceOrder = sourceOrder).repair(active.sources.sourceOrder.toSet())
        updateBinding(active.copy(sources = next))
        next
    }

    suspend fun updateSourceEnabled(source: String, enabled: Boolean): LocalAudioSourceConfig = locked {
        val active = readState().active ?: return@locked LocalAudioSourceConfig()
        if (source !in active.sources.sourceOrder) return@locked active.sources
        val next = active.sources.copy(disabledSources = if (enabled) active.sources.disabledSources - source else active.sources.disabledSources + source)
        updateBinding(active.copy(sources = next))
        next
    }

    suspend fun findAudio(term: String, reading: String): LocalAudioEntry? = locked {
        val active = readState().active ?: return@locked null
        val normalized = LocalAudioResolver.katakanaToHiragana(reading)
        val config = queryConfig(active)
        if (config.sourceOrder.all { it in config.disabledSources }) return@locked null
        LocalAudioResolver.resolve(term, normalized, database(active).queryEntries(term, normalized), config.sourceOrder, config.disabledSources)
    }

    suspend fun findAudioCandidates(term: String, reading: String): List<LocalAudioCandidate> = locked {
        val active = readState().active ?: return@locked emptyList()
        val normalized = LocalAudioResolver.katakanaToHiragana(reading)
        val config = queryConfig(active)
        if (config.sourceOrder.all { it in config.disabledSources }) return@locked emptyList()
        LocalAudioResolver.resolveCandidates(
            term, normalized, database(active).queryEntries(term, normalized), config.sourceOrder, config.disabledSources,
            generation = active.generation,
        )
    }

    suspend fun loadAudio(file: LocalAudioFile): ByteArray? = locked {
        val active = readState().active ?: return@locked null
        if (file.generation != null && file.generation != active.generation) return@locked null
        database(active).loadAudio(file.source, file.file)
    }

    private fun queryConfig(binding: LocalAudioBinding): LocalAudioSourceConfig {
        if (binding.sources.sourceOrder.isNotEmpty()) return binding.sources
        val config = binding.sources.repair(database(binding).querySources().toSet())
        updateBinding(binding.copy(sources = config))
        return config
    }

    private fun database(binding: LocalAudioBinding): LocalAudioDatabaseSession {
        session?.takeIf { sessionGeneration == binding.generation }?.let { return it }
        closeSession()
        val opened = if (binding.uri != null) access.openDocument(binding.uri) else access.openPrivate(privateFile(binding))
        try {
            if (binding.fingerprint.isNotEmpty() && binding.fingerprint != opened.fingerprint) {
                throw IOException("The audio database has changed. Select the file again.")
            }
            session = opened
            sessionGeneration = binding.generation
            return opened
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }

    private fun readState(): LocalAudioStoredState {
        state?.let { return it }
        val persisted = store.read()
        if (persisted != null) return persisted.also { state = it; cleanupAbandonedCopies(it) }
        val legacy = dbFile.takeIf { it.isFile }
        val sourcesFile = filesDir.resolve(AudioSettings.LocalAudioSourceConfigPath)
        val config = if (legacy != null && sourcesFile.isFile) {
            try {
                legacyJson.decodeFromString<LocalAudioSourceConfig>(sourcesFile.readText())
            } catch (error: Exception) {
                record(error)
                LocalAudioSourceConfig()
            }
        } else LocalAudioSourceConfig()
        val initial = LocalAudioStoredState(active = legacy?.let {
            LocalAudioBinding(UUID.randomUUID().toString(), "android.db", it.length(), privateFileName = it.name, sources = config)
        })
        if (legacy != null) store.write(initial)
        state = initial
        cleanupAbandonedCopies(initial)
        return initial
    }

    private fun cleanupAbandonedCopies(current: LocalAudioStoredState) {
        val protected = setOfNotNull(current.active?.privateFileName, current.retainedPrivate?.privateFileName)
        val generated = Regex("android-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.db(?:\\.tmp)?")
        filesDir.resolve("Audio").listFiles().orEmpty().forEach { file ->
            if (file.name !in protected && generated.matches(file.name) && file.isFile && !file.delete()) {
                record(IOException("Unable to delete abandoned private audio database ${file.name}."))
            }
        }
    }

    private fun updateBinding(binding: LocalAudioBinding) = publish(readState().copy(active = binding))
    private fun publish(next: LocalAudioStoredState) { store.write(next); state = next }
    private fun privateFile(binding: LocalAudioBinding) = filesDir.resolve("Audio").resolve(requireNotNull(binding.privateFileName))
    private fun closeSession() { session?.close(); session = null; sessionGeneration = null }

    private fun releaseOldGrant(old: LocalAudioBinding?, next: LocalAudioBinding?) {
        if (old?.uri != null && old.ownsGrant && old.uri != next?.uri) releaseGrantQuietly(old.uri)
    }
    private fun releaseGrantQuietly(uri: String) {
        try { access.releaseReadPermission(uri) } catch (error: Exception) { record(error) }
    }
    private fun record(error: Throwable) {
        ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.audio_local_database_read_failed), error)
    }
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                block()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { closeSession(); record(error); throw error }
        }
    }
}
