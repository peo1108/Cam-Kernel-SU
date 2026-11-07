package cam.caesar.vn.ui.viewmodel

import cam.caesar.vn.data.model.Module
import cam.caesar.vn.data.model.ModuleUpdateInfo
import cam.caesar.vn.data.model.RepoModule
import cam.caesar.vn.data.model.libraryUpdate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The module update check of the Module page, kept apart from Android so JVM tests drive it.
 *
 * Each module is checked once per state (updateJson, version, enabled/update/remove) and by one
 * [sync] call at a time; the result is kept until that state changes. [isEnabled] (the "Check for
 * module updates" switch) is read on every call: when it is off, the results are dropped and
 * nothing is fetched or kept.
 */
class ModuleUpdateChecker(
    private val isEnabled: () -> Boolean,
    private val checkModule: suspend (Module) -> ModuleUpdateInfo,
    /** Cam's module library, read once per [sync] that has modules to check */
    private val fetchLibrary: suspend () -> List<RepoModule>,
    private val checkTimeout: Duration = 5.seconds,
) {
    private data class Signature(
        val updateJson: String,
        val versionCode: Int,
        val enabled: Boolean,
        val update: Boolean,
        val remove: Boolean,
    )

    private data class Entry(val signature: Signature, val info: ModuleUpdateInfo)

    /** The [sync] call fetching a module, and for which state of it. */
    private class Fetch(val owner: Any, val signature: Signature)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Entry>()
    private val inFlight = mutableMapOf<String, Fetch>()

    /** The module states of the latest [sync]: a result for any other state is stale. */
    private var current = emptyMap<String, Signature>()

    private val _results = MutableStateFlow<Map<String, ModuleUpdateInfo>>(emptyMap())

    /** The update found per module id ([ModuleUpdateInfo.Empty] when there is none). */
    val results: StateFlow<Map<String, ModuleUpdateInfo>> = _results.asStateFlow()

    suspend fun sync(modules: List<Module>) {
        if (!isEnabled()) {
            clear()
            return
        }

        val owner = Any()
        val toFetch = mutableListOf<Pair<Module, Signature>>()
        mutex.withLock {
            current = modules.associate { it.id to it.signature() }
            cache.entries.retainAll { it.value.signature == current[it.key] }
            modules.forEach { module ->
                val signature = current.getValue(module.id)
                if (module.id in cache || inFlight[module.id]?.signature == signature) return@forEach
                // an older fetch for another state of the module may still run; this one wins
                inFlight[module.id] = Fetch(owner, signature)
                toFetch += module to signature
            }
            publish()
        }
        if (toFetch.isEmpty()) return

        try {
            val library = fetchLibrary()
            val fetched = coroutineScope {
                toFetch.map { (module, signature) ->
                    async {
                        val info = withTimeoutOrNull(checkTimeout) { checkModule(module) }
                            ?.takeIf { it != ModuleUpdateInfo.Empty }
                            ?: libraryUpdate(module, library)
                            ?: ModuleUpdateInfo.Empty
                        module.id to Entry(signature, info)
                    }
                }.awaitAll()
            }
            mutex.withLock {
                // turned off while this ran: keep nothing it found
                if (!isEnabled()) return@withLock
                fetched.forEach { (id, entry) ->
                    if (current[id] == entry.signature) cache[id] = entry
                }
                publish()
            }
        } finally {
            // also when cancelled, or the modules stay "in flight" and are never checked again
            withContext(NonCancellable) {
                mutex.withLock {
                    toFetch.forEach { (module, _) ->
                        if (inFlight[module.id]?.owner === owner) inFlight.remove(module.id)
                    }
                }
            }
        }
    }

    /**
     * Drops every result. Fetches still running are left alone: they keep nothing while the
     * switch is off, and when it is turned on again before they end their results are current.
     */
    private suspend fun clear() {
        mutex.withLock {
            cache.clear()
            publish()
        }
    }

    // under [mutex], so results are published in the order they were made
    private fun publish() {
        _results.value = cache.mapValues { it.value.info }
    }

    private fun Module.signature() = Signature(
        updateJson = updateJson,
        versionCode = versionCode,
        enabled = enabled,
        update = update,
        remove = remove,
    )
}
