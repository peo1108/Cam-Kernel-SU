package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipEditorTest {
    private lateinit var fake: FakeFs

    @Before
    fun setUp() {
        fake = FakeFs()
        fake.dir("/data")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("module.prop")); zip.write("id=x".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("system/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("system/bin/tool")); zip.write("tool".toByteArray()); zip.closeEntry()
            val raw = "stored-data".toByteArray()
            zip.putNextEntry(ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = raw.size.toLong()
                compressedSize = raw.size.toLong()
                crc = CRC32().apply { update(raw) }.value
            })
            zip.write(raw)
            zip.closeEntry()
        }
        fake.bytes("/data/m.zip", out.toByteArray(), perm = 0x180)
        fake.dir("/data/src")
        fake.file("/data/src/new.txt", "new")
        fake.dir("/data/src/pack")
        fake.file("/data/src/pack/a", "A")
    }

    private fun entries(): Map<String, Pair<Int, String>> {
        val result = linkedMapOf<String, Pair<Int, String>>()
        ZipInputStream(fake.openRead("/data/m.zip")).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result[e.name] = e.method to zip.readBytes().decodeToString()
            }
        }
        return result
    }

    private fun answer(choice: ConflictChoice) = object : OpListener {
        override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) = Resolution(choice)
    }

    @Test
    fun deletesAndRenamesFolders() {
        ZipEditor(fake).apply("/data/m.zip", ZipEditor.Edits(deletes = setOf("system"), renames = mapOf("module.prop" to "info/module.prop")))
        assertEquals(setOf("info/module.prop", "resources.arsc"), entries().keys)
        assertEquals("id=x", entries().getValue("info/module.prop").second)
    }

    @Test
    fun keepsStoredEntriesStoredAndTheFileMode() {
        ZipEditor(fake).apply("/data/m.zip", ZipEditor.Edits(deletes = setOf("module.prop")))
        val arsc = entries().getValue("resources.arsc")
        assertEquals(ZipEntry.STORED, arsc.first)
        assertEquals("stored-data", arsc.second)
        assertEquals(0x180, fake.node("/data/m.zip").perm)
        assertTrue(fake.names("/data").none { it.endsWith(RootFs.TMP_SUFFIX) })
    }

    @Test
    fun addsFilesAndFoldersFromDisk() {
        val editor = ZipEditor(fake, answer(ConflictChoice.OVERWRITE))
        val (adds, dirs) = editor.additionsFrom(listOf("/data/src/new.txt", "/data/src/pack"), "system/bin")
        editor.apply("/data/m.zip", ZipEditor.Edits(adds = adds, mkdirs = dirs))
        val e = entries()
        assertEquals("new", e.getValue("system/bin/new.txt").second)
        assertEquals("A", e.getValue("system/bin/pack/a").second)
        assertTrue("system/bin/pack/" in e)
    }

    @Test
    fun conflictsOverwriteSkipOrKeepBoth() {
        fun replace(choice: ConflictChoice): Map<String, Pair<Int, String>> {
            setUp()
            val add = ZipEditor.Addition("module.prop", 4) { "id=y".byteInputStream() }
            ZipEditor(fake, answer(choice)).apply("/data/m.zip", ZipEditor.Edits(adds = listOf(add)))
            return entries()
        }
        assertEquals("id=y", replace(ConflictChoice.OVERWRITE).getValue("module.prop").second)
        assertEquals("id=x", replace(ConflictChoice.SKIP).getValue("module.prop").second)
        val both = replace(ConflictChoice.RENAME)
        assertEquals("id=x", both.getValue("module.prop").second)
        assertEquals("id=y", both.getValue("module (1).prop").second)
    }

    @Test
    fun cancelLeavesTheArchiveUntouched() {
        val before = fake.data("/data/m.zip").copyOf()
        try {
            ZipEditor(fake, isCancelled = { true }).apply("/data/m.zip", ZipEditor.Edits(deletes = setOf("system")))
            fail("expected cancel")
        } catch (_: OpCancelledException) {
        }
        assertTrue(before.contentEquals(fake.data("/data/m.zip")))
        assertTrue(fake.names("/data").none { it.endsWith(RootFs.TMP_SUFFIX) })
    }

    @Test
    fun helpers() {
        assertTrue(ZipEditor.under("a/b/c", "a/b"))
        assertTrue(!ZipEditor.under("a/bc", "a/b"))
        assertEquals("x/c", ZipEditor.rename("a/b/c", mapOf("a/b" to "x")))
        assertEquals("d/e (2).txt", ZipEditor.uniqueName("d/e.txt", setOf("d/e.txt", "d/e (1).txt")))
    }
}
