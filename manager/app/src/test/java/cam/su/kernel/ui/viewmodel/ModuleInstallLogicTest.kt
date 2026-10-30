package cam.su.kernel.ui.viewmodel

import cam.su.kernel.files.FileEntry
import cam.su.kernel.ui.util.module.parseModuleProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleInstallLogicTest {

    private fun entry(name: String, type: Int = FileEntry.TYPE_FILE, mtime: Long = 0, targetIsDir: Boolean = false) = FileEntry(
        name = name,
        path = "/sdcard/$name",
        type = type,
        size = 1,
        mtime = mtime,
        mode = 0,
        uid = 0,
        gid = 0,
        owner = "",
        group = "",
        context = null,
        targetIsDir = targetIsDir,
    )

    @Test
    fun browserListsFoldersByNameThenZipsNewestFirst() {
        val listed = installEntries(
            listOf(
                entry("old.zip", mtime = 1),
                entry("photo.png", mtime = 9),
                entry("beta", FileEntry.TYPE_DIR),
                entry("NEW.ZIP", mtime = 5),
                entry("Alpha", FileEntry.TYPE_DIR),
                entry("linked", FileEntry.TYPE_LINK, targetIsDir = true),
            ),
        )
        assertEquals(listOf("Alpha", "beta", "linked", "NEW.ZIP", "old.zip"), listed.map { it.name })
    }

    @Test
    fun aTerminalClearStartsTheLogOver() {
        assertEquals("one\ntwo\n", appendFlashOutput("one\n", "two"))
        assertEquals("fresh\n", appendFlashOutput("one\n", "\u001b[H\u001b[Jfresh"))
    }

    @Test
    fun modulePropGivesWhatTheCardShows() {
        val prop = parseModuleProp(
            """
            # a comment
            id=y700_tuner
            name = Y700 Tuner
            version=v0.8.1
            versionCode=5
            author=Cam
            description=Tunes = things
            id=second_wins_not
            """.trimIndent(),
        )!!
        assertEquals("y700_tuner", prop.id)
        assertEquals("Y700 Tuner", prop.name)
        assertEquals("v0.8.1", prop.version)
        assertEquals("Cam", prop.author)
        assertEquals("Tunes = things", prop.description)
    }

    @Test
    fun aModuleWithoutANameShowsItsId() {
        assertEquals("only_id", parseModuleProp("id=only_id")!!.name)
    }

    @Test
    fun aPropWithoutAnIdIsNoModule() {
        assertNull(parseModuleProp("name=No id\nversion=1"))
        assertNull(parseModuleProp("id=\nname=Empty id"))
        assertNull(parseModuleProp(""))
    }
}
