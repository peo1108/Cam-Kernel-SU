package me.weishu.kernelsu.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleConflictTest {

    @Test
    fun parsesConflicts() {
        val json = """[{"kind":"replace","path":"/system/media/audio","modules":["a","b"]}]"""
        assertEquals(
            listOf(ModuleConflict(ConflictKind.Replace, "/system/media/audio", listOf("a", "b"))),
            parseModuleConflicts(json)
        )
    }

    @Test
    fun unknownKindSkipped() {
        val json = """[{"kind":"x","path":"/a","modules":["a","b"]},{"kind":"prop","path":"ro.x","modules":["a","b"]}]"""
        assertEquals(listOf(ModuleConflict(ConflictKind.Prop, "ro.x", listOf("a", "b"))), parseModuleConflicts(json))
    }

    @Test
    fun corruptConflictsAreEmpty() {
        assertEquals(emptyList<ModuleConflict>(), parseModuleConflicts("[{"))
    }

    @Test
    fun forModuleFilters() {
        val conflicts = listOf(
            ModuleConflict(ConflictKind.File, "/system/etc/hosts", listOf("a", "b")),
            ModuleConflict(ConflictKind.Prop, "ro.x", listOf("a", "c")),
        )
        assertEquals(listOf(conflicts[0]), conflicts.forModule("b"))
    }
}
