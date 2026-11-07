package cam.caesar.vn.ui.viewmodel

import cam.caesar.vn.data.model.Module
import cam.caesar.vn.data.model.ModuleUpdateInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUpdateCheckerTest {

    private fun module(id: String, versionCode: Int = 1) = Module(
        id = id,
        name = id,
        author = "",
        version = "v$versionCode",
        versionCode = versionCode,
        description = "",
        enabled = true,
        update = false,
        remove = false,
        updateJson = "https://example.invalid/$id.json",
        hasWebUi = false,
        hasActionScript = false,
        metamodule = false,
        actionIconPath = null,
        webUiIconPath = null,
    )

    private fun update(id: String) = ModuleUpdateInfo("https://example.invalid/$id.zip", "v9", "")

    /** A fake update server: counts calls; [gate], when set, holds every answer until completed. */
    private class Server {
        var enabled = true
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        var answer: (Module) -> ModuleUpdateInfo = { ModuleUpdateInfo("https://example.invalid/${it.id}.zip", "v9", "") }

        fun checker() = ModuleUpdateChecker(
            isEnabled = { enabled },
            checkModule = { module ->
                calls += "${module.id}@${module.versionCode}"
                started.complete(Unit)
                gate?.await()
                answer(module)
            },
            fetchLibrary = { emptyList() },
        )
    }

    @Test
    fun keepsResultsUntilTheModuleChanges() = runBlocking {
        val server = Server()
        val checker = server.checker()

        checker.sync(listOf(module("a")))
        checker.sync(listOf(module("a")))
        assertEquals(listOf("a@1"), server.calls)
        assertEquals(mapOf("a" to update("a")), checker.results.value)

        checker.sync(listOf(module("a", versionCode = 2)))
        assertEquals(listOf("a@1", "a@2"), server.calls)
    }

    @Test
    fun dropsTheResultOfARemovedModule() = runBlocking {
        val server = Server()
        val checker = server.checker()

        checker.sync(listOf(module("a"), module("b")))
        checker.sync(listOf(module("a")))
        assertEquals(setOf("a"), checker.results.value.keys)
    }

    @Test
    fun switchOffDropsResultsAndFetchesNothing() = runBlocking {
        val server = Server()
        val checker = server.checker()
        checker.sync(listOf(module("a")))

        server.enabled = false
        checker.sync(listOf(module("a", versionCode = 2)))
        assertEquals(emptyMap<String, ModuleUpdateInfo>(), checker.results.value)
        assertEquals(listOf("a@1"), server.calls)
    }

    @Test
    fun switchOffDuringACheckKeepsNothingItFound() = runBlocking {
        val server = Server().apply { gate = CompletableDeferred() }
        val checker = server.checker()

        val job = launch { checker.sync(listOf(module("a"))) }
        server.started.await()
        server.enabled = false
        server.gate!!.complete(Unit)
        job.join()
        assertTrue(checker.results.value.isEmpty())
    }

    @Test
    fun cancelledCheckDoesNotStrandTheModule() = runBlocking {
        val server = Server().apply { gate = CompletableDeferred() }
        val checker = server.checker()

        val job = launch { checker.sync(listOf(module("a"))) }
        server.started.await()
        job.cancel()
        job.join()

        server.gate = null
        checker.sync(listOf(module("a")))
        assertEquals(listOf("a@1", "a@1"), server.calls)
        assertEquals(mapOf("a" to update("a")), checker.results.value)
    }

    @Test
    fun turningBackOnDuringACheckDoesNotFetchTwice() = runBlocking {
        val server = Server().apply { gate = CompletableDeferred() }
        val checker = server.checker()

        val first = launch { checker.sync(listOf(module("a"))) }
        server.started.await()
        server.enabled = false
        checker.sync(listOf(module("a")))
        server.enabled = true
        checker.sync(listOf(module("a")))
        assertEquals(listOf("a@1"), server.calls)

        server.gate!!.complete(Unit)
        first.join()
        assertEquals(mapOf("a" to update("a")), checker.results.value)
    }

    @Test
    fun aCheckOfAnOlderModuleStateDoesNotOverwriteTheCurrentOne() = runBlocking {
        val oldGate = CompletableDeferred<Unit>()
        val server = Server()
        val checker = server.checker()
        // version 1 waits, version 2 (the module was just updated) has no update
        server.answer = { if (it.versionCode == 2) ModuleUpdateInfo.Empty else update(it.id) }
        server.gate = oldGate

        val old = launch { checker.sync(listOf(module("a", versionCode = 1))) }
        server.started.await()
        server.gate = null
        checker.sync(listOf(module("a", versionCode = 2)))
        assertEquals(listOf("a@1", "a@2"), server.calls)

        oldGate.complete(Unit)
        old.join()
        assertEquals(mapOf("a" to ModuleUpdateInfo.Empty), checker.results.value)
    }
}
