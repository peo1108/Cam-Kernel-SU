package cam.su.kernel.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryUpdateTest {

    private fun module(id: String = "y700bridge", versionCode: Int = 10010, enabled: Boolean = true, update: Boolean = false) = Module(
        id = id,
        name = id,
        author = "cam",
        version = "v1.0.10",
        versionCode = versionCode,
        description = "",
        enabled = enabled,
        update = update,
        remove = false,
        updateJson = "",
        hasWebUi = false,
        hasActionScript = false,
        metamodule = false,
        actionIconPath = null,
        webUiIconPath = null,
    )

    private fun release(id: String = "y700bridge", versionCode: Int = 10011, url: String? = "https://caesarvn.xyz/feed/modules/y700bridge-v1.0.11.zip") = RepoModule(
        moduleId = id,
        moduleName = id,
        authors = "cam",
        authorList = emptyList(),
        summary = "",
        metamodule = false,
        stargazerCount = 0,
        updatedAt = "",
        createdAt = "",
        latestRelease = "v1.0.11",
        latestReleaseTime = "",
        latestVersionCode = versionCode,
        latestAsset = url?.let { ReleaseAsset(name = it.substringAfterLast('/'), downloadUrl = it, size = 0) },
    )

    @Test
    fun newerReleaseIsAnUpdate() {
        val info = libraryUpdate(module(), listOf(release(id = "other", versionCode = 99999), release()))
        assertEquals(
            ModuleUpdateInfo(
                downloadUrl = "https://caesarvn.xyz/feed/modules/y700bridge-v1.0.11.zip",
                version = "v1.0.11",
                changelog = "",
                fromLibrary = true,
            ),
            info,
        )
    }

    @Test
    fun sameOrOlderReleaseIsNot() {
        assertNull(libraryUpdate(module(versionCode = 10011), listOf(release())))
        assertNull(libraryUpdate(module(versionCode = 20000), listOf(release())))
    }

    @Test
    fun moduleNotInTheLibrary() {
        assertNull(libraryUpdate(module(id = "local-only"), listOf(release())))
        assertNull(libraryUpdate(module(), emptyList()))
    }

    @Test
    fun releaseWithoutAZip() {
        assertNull(libraryUpdate(module(), listOf(release(url = null))))
        assertNull(libraryUpdate(module(), listOf(release(url = " "))))
    }

    @Test
    fun withoutAVersionCodeTheNamesAreCompared() {
        // modules.json lists no versionCode for most modules
        assertEquals("v1.0.11", libraryUpdate(module(), listOf(release(versionCode = 0)))?.version)
        assertNull(libraryUpdate(module().copy(version = "v1.0.11"), listOf(release(versionCode = 0))))
        assertNull(libraryUpdate(module().copy(version = "1.0.12 (512)"), listOf(release(versionCode = 0))))
        assertNull(libraryUpdate(module().copy(version = "beta"), listOf(release(versionCode = 0))))
    }

    @Test
    fun versionNames() {
        assertEquals(1, compareVersionNames("v4.0.11", "4.0.10 (930)"))
        assertEquals(-1, compareVersionNames("4.0.11", "4.0.11 (930)"))
        assertEquals(0, compareVersionNames("v2.10", "2.10"))
        assertEquals(1, compareVersionNames("v2.10", "v2.9"))
        assertEquals(0, compareVersionNames("v01.2", "1.2"))
        assertEquals(1, compareVersionNames("2026.10.14", "2026.9.30"))
        assertNull(compareVersionNames("latest", "1.0"))
    }

    @Test
    fun disabledOrPendingModulesGetNone() {
        assertNull(libraryUpdate(module(enabled = false), listOf(release())))
        assertNull(libraryUpdate(module(update = true), listOf(release())))
    }
}
