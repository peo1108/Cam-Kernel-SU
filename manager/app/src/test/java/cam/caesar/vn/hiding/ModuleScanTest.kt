package cam.su.kernel.hiding

import cam.su.kernel.data.model.HidingAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleScanTest {

    // what `camd module list` prints: every value a string
    private fun module(
        id: String,
        versionCode: String = "1",
        enabled: Boolean = true,
        update: Boolean = false,
        remove: Boolean = false,
    ) = """{"id":"$id","name":"Name $id","version":"v$versionCode","versionCode":"$versionCode",""" +
        """"enabled":"$enabled","update":"$update","remove":"$remove"}"""

    private fun list(vararg modules: String) = ModuleScan.parseModules("[${modules.joinToString(",")}]")!!

    private fun finding(id: String, vararg modules: String, leak: Boolean = true) =
        HidingAudit.Finding(id = id, leak = leak, items = emptyList(), fix = null, modules = modules.toList())

    @Test
    fun readsTheModuleList() {
        val modules = list(module("a"), module("b", versionCode = "2", enabled = false, update = true))
        assertEquals(listOf("a", "b"), modules.map { it.id })
        assertEquals("1:v1", modules[0].stamp)
        assertEquals(listOf(true, false), modules.map { it.running })
        assertEquals(true, modules[1].pending)
        assertNull(ModuleScan.parseModules("not json"))
    }

    @Test
    fun newUpdatedAndEnabledAgainModulesChanged() {
        val baseline = mapOf("same" to "1:v1", "updated" to "1:v1")
        val now = list(
            module("same"),
            module("updated", versionCode = "2"),
            module("installed"),
            // was disabled at the last check, so it is not in the baseline
            module("enabledAgain"),
            module("off", enabled = false),
        )
        assertEquals(listOf("updated", "installed", "enabledAgain"), ModuleScan.changed(baseline, now))
    }

    @Test
    fun aModuleWaitingForRebootChangesOnTheBootThatMountsIt() {
        // installed and updated, not mounted yet: the check of this boot leaves them alone
        val waiting = list(module("a", versionCode = "2", update = true), module("b", update = true))
        val baseline = mapOf("a" to "1:v1")
        assertEquals(emptyList<String>(), ModuleScan.changed(baseline, waiting))
        val next = ModuleScan.nextBaseline(baseline, waiting)
        assertEquals(mapOf("a" to "1:v1"), next)
        // the next boot mounts them
        val mounted = list(module("a", versionCode = "2"), module("b"))
        assertEquals(listOf("a", "b"), ModuleScan.changed(next, mounted))
        assertEquals(mapOf("a" to "2:v2", "b" to "1:v1"), ModuleScan.nextBaseline(next, mounted))
    }

    @Test
    fun disabledAndRemovedModulesLeaveTheBaseline() {
        val baseline = mapOf("off" to "1:v1", "gone" to "1:v1", "kept" to "1:v1")
        val now = list(module("off", enabled = false), module("gone", remove = true, update = true), module("kept"))
        assertEquals(mapOf("kept" to "1:v1"), ModuleScan.nextBaseline(baseline, now))
    }

    @Test
    fun leaksAreTheOnesNamingAChangedModule() {
        val audit = HidingAudit(
            listOf(
                finding("moduleMounts", "old", "new"),
                finding("appMaps", "new"),
                finding("maps", "other"),
                finding("lsposed", "new", leak = false),
                finding("appMounts", "new"),
                finding("props"),
            ),
            fixable = 0,
        )
        assertEquals(
            mapOf("new" to listOf("moduleMounts", "appMaps")),
            ModuleScan.leaksBy(audit, changed = listOf("new", "quiet"), ignored = setOf("appMounts")),
        )
        assertEquals(emptyMap<String, List<String>>(), ModuleScan.leaksBy(audit, emptyList(), emptySet()))
    }

    @Test
    fun leaksFollowTheOrderModulesChanged() {
        val audit = HidingAudit(listOf(finding("moduleMounts", "b", "a"), finding("appMaps", "b")), fixable = 0)
        val leaks = ModuleScan.leaksBy(audit, changed = listOf("a", "b"), ignored = emptySet())
        assertEquals(listOf("a", "b"), leaks.keys.toList())
        assertEquals(listOf("moduleMounts", "appMaps"), leaks["b"])
    }

    @Test
    fun baselineReadsBack() {
        val baseline = mapOf("a" to "1:v1", "b" to ":")
        assertEquals(baseline, ModuleScan.parseBaseline(ModuleScan.baselineToJson(baseline)))
        assertNull(ModuleScan.parseBaseline("[]"))
    }
}
