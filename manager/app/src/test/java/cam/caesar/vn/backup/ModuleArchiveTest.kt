package cam.su.kernel.backup

import cam.su.kernel.hiding.ModuleRisk
import cam.su.kernel.hiding.ModuleRiskScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleArchiveTest {

    private val list = """
        {"modules":[
          {"id":"zygisksu","name":"Zygisk Next","author":"Dr-TSNG","version":"1.2","versionCode":512,"enabled":true,"installed":"1.1"},
          {"id":"gyro","name":"","author":"x","version":"2","versionCode":2,"enabled":false,"installed":null},
          {"id":"evil","name":"Evil","author":"y","version":"1","versionCode":1,"enabled":true,"installed":null}
        ]}
    """.trimIndent()

    private val risks = ModuleRiskScanner(
        listOf(ModuleRisk(ModuleRisk.Level.Block, listOf("evil"), emptyList(), "độc", "bad"))
    )

    @Test
    fun parsesListWithInstalledVersionAndRisk() {
        val modules = ModuleArchive.parseModules(list, risks)
        assertEquals(listOf("zygisksu", "gyro", "evil"), modules.map { it.id })
        assertEquals("1.1", modules[0].installed)
        assertNull(modules[1].installed)
        assertFalse(modules[1].enabled)
        assertEquals("gyro", modules[1].label)
        assertNull(modules[0].risk)
        assertEquals("bad", modules[2].risk?.reasonEn)
    }

    @Test
    fun blockedModulesAreRestorableOnlyWhenBlockingIsOff() {
        val evil = ModuleArchive.parseModules(list, risks)[2]
        assertFalse(ModuleArchive.restorable(evil, blockRisks = true))
        assertTrue(ModuleArchive.restorable(evil, blockRisks = false))
    }

    @Test
    fun exportManifestParsesWithoutInstalledField() {
        val manifest = """{"format":"cam-modules","version":1,"created":1,"modules":[{"id":"a","name":"A","version":"1","versionCode":1,"enabled":true}]}"""
        val module = ModuleArchive.parseModules(manifest).single()
        assertEquals("a", module.id)
        assertNull(module.installed)
    }

    @Test
    fun parsesImportResult() {
        val result = ModuleArchive.parseImport("""{"imported":["a","b"],"failed":[{"id":"c","error":"module.prop says id 'd', not 'c'"}]}""")
        assertEquals(listOf("a", "b"), result.imported)
        assertEquals(mapOf("c" to "module.prop says id 'd', not 'c'"), result.failed)
    }
}
