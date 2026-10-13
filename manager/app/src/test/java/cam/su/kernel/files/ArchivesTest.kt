package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {
    private lateinit var fake: FakeFs

    @Before
    fun setUp() {
        fake = FakeFs()
        fake.dir("/data")
        fake.dir("/data/out")
    }

    private fun zip(vararg entries: Pair<String, String?>): File {
        val file = File.createTempFile("archives", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                text?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return file
    }

    private val quiet = object : OpListener {
        override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) = Resolution(ConflictChoice.OVERWRITE)
    }

    @Test
    fun pathsInsideArchives() {
        assertTrue(Archives.isInside("/a.zip!/x"))
        assertFalse(Archives.isInside("/a.zip"))
        assertEquals("/sd/a.zip" to "dir/f", Archives.split("/sd/a.zip!/dir/f/"))
        assertEquals("/sd/a.zip" to "", Archives.split("/sd/a.zip!/"))
        assertEquals("/sd/a.zip!/dir", Archives.parent("/sd/a.zip!/dir/f"))
        assertEquals("/sd/a.zip!/", Archives.parent("/sd/a.zip!/dir"))
        assertEquals("/sd", Archives.parent("/sd/a.zip!/"))
        assertEquals("/sd", PanelNav("/sd/a.zip!/").parent)
        assertNull(PanelNav("/").parent)
        // "!/" only splits after an archive name: a folder may be called "Wow!"
        assertFalse(Archives.isInside("/sdcard/Wow!/x"))
        assertEquals("/sdcard/Wow!", PanelNav("/sdcard/Wow!/x").parent)
        assertEquals("/sd/Wow!/a.zip" to "f", Archives.split("/sd/Wow!/a.zip!/f"))
        assertEquals("/sd/Wow!", PanelNav("/sd/Wow!/a.zip!/").parent)
    }

    @Test
    fun knowsWhatCanBeBrowsed() {
        assertTrue(Archives.canBrowse("Magisk.ZIP"))
        assertTrue(Archives.canBrowse("x.apk"))
        assertTrue(Archives.canBrowse("game.xapk"))
        assertTrue(Archives.canBrowse("rootfs.tar.gz"))
        assertTrue(Archives.canBrowse("a.tgz"))
        assertFalse(Archives.canBrowse("log.gz"))
        assertFalse(Archives.canBrowse("init_boot.img"))
        assertTrue(Archives.isApkBundle("a.apks"))
        assertFalse(Archives.isApkBundle("a.apk"))
    }

    @Test
    fun listsFoldersFromPrefixes() {
        val file = zip("META-INF/MANIFEST.MF" to "m", "module.prop" to "id=x", "system/bin/tool" to "t", "system/" to null)
        Archives.open(file).use { reader ->
            val root = Archives.list(reader, "/sd/m.zip", "")
            assertEquals(listOf("META-INF", "system", "module.prop"), root.map { it.name })
            assertTrue(root[0].isDir && root[1].isDir && !root[2].isDir)
            assertEquals("/sd/m.zip!/system", root[1].path)
            assertEquals(4L, root[2].size)
            assertEquals(listOf("bin"), Archives.list(reader, "/sd/m.zip", "system").map { it.name })
            assertEquals(listOf("tool"), Archives.list(reader, "/sd/m.zip", "system/bin").map { it.name })
        }
    }

    @Test
    fun extractsFilesAndFolders() {
        val file = zip("module.prop" to "id=x", "system/bin/tool" to "tool", "system/etc/hosts" to "h")
        Archives.open(file).use { reader ->
            val result = ArchiveOps(fake, quiet).extract(reader, listOf("system", "module.prop"), "/data/out")
            assertEquals(3, result.items)
        }
        assertEquals("tool", fake.text("/data/out/system/bin/tool"))
        assertEquals("h", fake.text("/data/out/system/etc/hosts"))
        assertEquals("id=x", fake.text("/data/out/module.prop"))
        assertTrue(fake.names("/data/out").none { it.endsWith(RootFs.TMP_SUFFIX) })
    }

    @Test
    fun extractsANestedFileUnderItsOwnName() {
        val file = zip("a/b/c.txt" to "c")
        Archives.open(file).use { ArchiveOps(fake, quiet).extract(it, listOf("a/b/c.txt"), "/data/out") }
        assertEquals(listOf("c.txt"), fake.names("/data/out"))
    }

    @Test
    fun extractRejectsZipSlip() {
        val file = zip("../../evil" to "x")
        try {
            Archives.open(file).use { ArchiveOps(fake, quiet).extract(it, listOf(""), "/data/out") }
            fail("expected FsException")
        } catch (e: FsException) {
            assertEquals(Errno.EINVAL, e.errno)
        }
        assertFalse(fake.names("/").contains("evil"))
        assertFalse(fake.names("/data").contains("evil"))
    }

    @Test
    fun compressThenReadBack() {
        fake.dir("/data/m")
        fake.dir("/data/m/system")
        fake.file("/data/m/module.prop", "id=x")
        fake.file("/data/m/system/a", "aaa")
        fake.link("/data/m/l", "module.prop")

        val result = ArchiveOps(fake, quiet).compress(listOf("/data/m"), "/data/m.zip")
        assertEquals(2, result.items)
        assertEquals(1, result.skipped) // the symlink

        val local = File.createTempFile("back", ".zip").apply { deleteOnExit() }
        fake.openRead("/data/m.zip").use { input -> local.outputStream().use { input.copyTo(it) } }
        Archives.open(local).use { reader ->
            assertEquals(setOf("m", "m/system", "m/module.prop", "m/system/a"), reader.entries.map { it.name }.toSet())
            assertEquals("aaa", reader.open("m/system/a").readBytes().decodeToString())
        }
    }

    @Test
    fun installerHelpers() {
        assertEquals(1234, ApkInstaller.parseSession("Success: created install session [1234]"))
        assertNull(ApkInstaller.parseSession("Failure [INSTALL_FAILED]"))
        val entries = listOf(
            ArchiveEntry("config.arm64_v8a.apk", false, 1, 0),
            ArchiveEntry("base.apk", false, 1, 0),
            ArchiveEntry("icon.png", false, 1, 0),
            ArchiveEntry("Android/obb/com.x/main.1.com.x.obb", false, 1, 0),
            ArchiveEntry("nested/other.apk", false, 1, 0),
        )
        assertEquals(listOf("base.apk", "config.arm64_v8a.apk"), ApkInstaller.bundleApks(entries).map { it.name })
        assertEquals(listOf("Android/obb/com.x/main.1.com.x.obb"), ApkInstaller.bundleObbs(entries).map { it.name })
    }
}
