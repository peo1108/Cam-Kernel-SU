package cam.su.kernel.backup

import cam.su.kernel.CamNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {
    private val rootApp = SavedProfile(
        key = "com.termux",
        userId = 0,
        profile = CamNative.Profile(
            name = "com.termux",
            currentUid = 10123,
            allowSu = true,
            rootUseDefault = false,
            uid = 0,
            gid = 0,
            groups = listOf(1004, 3003),
            capabilities = listOf(21),
            context = "u:r:magisk:s0",
            namespace = 2,
        ),
        rules = "allow appdomain shell_exec file execute",
    )
    private val bank = SavedProfile(
        "vn.bank",
        10,
        CamNative.Profile(name = "vn.bank", currentUid = 1010200, nonRootUseDefault = false, umountModules = true),
    )

    private val backup = ConfigBackup(
        created = 1_760_400_000_000,
        manager = "1.1.7",
        device = "Lenovo TB323FU",
        features = SavedFeatures(suCompat = true, kernelUmount = true, selinuxHide = false, defaultUmountModules = true),
        profiles = listOf(rootApp, bank),
        modules = listOf(SavedModule("zygisk_next", "Zygisk Next", "1.2", true), SavedModule("old", "", "", false)),
        bookmarks = listOf("/data/adb/modules", "/sdcard/Download"),
    )

    @Test
    fun roundTrips() {
        val back = ConfigBackup.parse(backup.toJson())
        // a uid belongs to one phone: the file leaves it out, a restore looks it up again
        val withoutUids = backup.copy(profiles = backup.profiles.map { it.copy(profile = it.profile.copy(currentUid = 0)) })
        assertEquals(withoutUids, back)
        // hideBootloader was unknown and stays unknown, not "off"
        assertEquals(null, back.features.hideBootloader)
    }

    @Test
    fun refusesOtherFilesAndNewerVersions() {
        assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse("""{"format":"other","version":1}""") }
        assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse("""{"format":"cam-config","version":99}""") }
        val empty = ConfigBackup.parse("""{"format":"cam-config","version":1}""")
        assertTrue(empty.profiles.isEmpty() && empty.modules.isEmpty() && empty.bookmarks.isEmpty())
    }

    @Test
    fun planMapsAppsToThisPhonesUids() {
        // the termux uid differs on the new phone; the bank's second user is not there
        val plan = RestorePlan.of(
            backup,
            installed = mapOf(("com.termux" to 0) to 10234, ("vn.bank" to 0) to 10300),
            modules = mapOf("zygisk_next" to false, "old" to false, "other" to true),
            bookmarks = listOf("/data/adb/modules"),
        )
        assertEquals(listOf(10234), plan.profiles.map { it.profile.currentUid })
        assertEquals("com.termux", plan.profiles.single().profile.name)
        assertEquals(rootApp.rules, plan.profiles.single().rules)
        assertEquals(listOf("vn.bank (10)"), plan.missingApps)
        // only what differs gets switched
        assertEquals(listOf("zygisk_next"), plan.modules.map { it.id })
        assertTrue(plan.missingModules.isEmpty())
        assertEquals(listOf("/sdcard/Download"), plan.newBookmarks)
    }

    @Test
    fun planListsModulesThatAreNotInstalled() {
        val plan = RestorePlan.of(backup, emptyMap(), emptyMap(), emptyList())
        assertEquals(listOf("zygisk_next", "old"), plan.missingModules.map { it.id })
        assertEquals(2, plan.missingApps.size)
    }
}
