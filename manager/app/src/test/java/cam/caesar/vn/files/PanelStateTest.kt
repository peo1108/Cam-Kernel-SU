package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelStateTest {

    private fun entry(name: String, dir: Boolean = false, size: Long = 0, mtime: Long = 0) = FileEntry(
        name = name, path = "/x/$name", type = if (dir) FileEntry.TYPE_DIR else FileEntry.TYPE_FILE,
        size = size, mtime = mtime, mode = 0, uid = 0, gid = 0, owner = "root", group = "root", context = null,
    )

    private fun names(list: List<FileEntry>) = list.map { it.name }

    @Test
    fun foldersFirstThenNaturalNames() {
        val sorted = FileSort.apply(
            listOf(entry("file10"), entry("b", dir = true), entry("File2"), entry("a", dir = true), entry("file1")),
            SortOptions(),
        )
        assertEquals(listOf("a", "b", "file1", "File2", "file10"), names(sorted))
    }

    @Test
    fun descendingKeepsFoldersFirst() {
        val sorted = FileSort.apply(
            listOf(entry("a"), entry("d", dir = true), entry("b")),
            SortOptions(descending = true),
        )
        assertEquals(listOf("d", "b", "a"), names(sorted))
    }

    @Test
    fun sortsBySizeTimeAndType() {
        val list = listOf(entry("a.zip", size = 30, mtime = 1), entry("b.apk", size = 10, mtime = 3), entry("c.txt", size = 20, mtime = 2))
        assertEquals(listOf("b.apk", "c.txt", "a.zip"), names(FileSort.apply(list, SortOptions(by = SortBy.SIZE))))
        assertEquals(listOf("a.zip", "c.txt", "b.apk"), names(FileSort.apply(list, SortOptions(by = SortBy.TIME))))
        assertEquals(listOf("b.apk", "c.txt", "a.zip"), names(FileSort.apply(list, SortOptions(by = SortBy.TYPE))))
    }

    @Test
    fun hidesDotFilesWhenAsked() {
        val list = listOf(entry(".hidden"), entry("shown"))
        assertEquals(listOf("shown"), names(FileSort.apply(list, SortOptions(showHidden = false))))
        assertEquals(2, FileSort.apply(list, SortOptions(showHidden = true)).size)
    }

    @Test
    fun naturalOrderHandlesLeadingZerosAndLongNumbers() {
        assertTrue(NaturalOrder.compare("v2", "v10") < 0)
        assertTrue(NaturalOrder.compare("a007", "a7") != 0)
        assertTrue(NaturalOrder.compare("x99999999999999999999", "x100000000000000000000") < 0)
        assertTrue(NaturalOrder.compare("abc", "abcd") < 0)
    }

    @Test
    fun historyBackAndForward() {
        var nav = PanelNav("/")
        nav = nav.go("/data").go("/data/adb").go("/data/adb")
        assertEquals(listOf("/", "/data"), nav.back)
        nav = nav.goBack()!!
        assertEquals("/data", nav.path)
        nav = nav.goForward()!!
        assertEquals("/data/adb", nav.path)
        nav = nav.goBack()!!.go("/sdcard")
        assertTrue(nav.forward.isEmpty())
        assertNull(PanelNav("/").goBack())
        assertNull(PanelNav("/").parent)
        assertEquals("/data", PanelNav("/data/adb").parent)
    }

    @Test
    fun breadcrumb() {
        assertEquals(listOf("/" to "/"), FileFormat.breadcrumb("/"))
        assertEquals(
            listOf("/" to "/", "data" to "/data", "adb" to "/data/adb"),
            FileFormat.breadcrumb("/data/adb"),
        )
    }

    @Test
    fun sizes() {
        assertEquals("0 B", FileFormat.size(0))
        assertEquals("1023 B", FileFormat.size(1023))
        assertEquals("1.0 KB", FileFormat.size(1024))
        assertEquals("1.5 MB", FileFormat.size(1572864))
        assertEquals("512 MB", FileFormat.size(512L * 1024 * 1024))
    }

    @Test
    fun permissions() {
        assertEquals("drwxr-xr-x", FileFormat.permissions(0x4000 or 0x1ED))
        assertEquals("-rw-r--r--", FileFormat.permissions(0x8000 or 0x1A4))
        assertEquals("lrwxrwxrwx", FileFormat.permissions(0xA000 or 0x1FF))
        assertEquals("-rwsr-sr-x", FileFormat.permissions(0x8000 or 0xDED)) // 6755
        assertEquals("drwxrwxrwt", FileFormat.permissions(0x4000 or 0x3FF)) // 1777
        assertEquals("-rwSr--r--", FileFormat.permissions(0x8000 or 0x9A4)) // 4644
        assertEquals("0755", FileFormat.octal(0x4000 or 0x1ED))
    }

    @Test
    fun selectionRangeInvertSameType() {
        val list = listOf(entry("d", dir = true), entry("a.txt"), entry("b.zip"), entry("c.txt"), entry("e.zip"))
        val paths = list.map { it.path }
        assertEquals(paths.subList(1, 4).toSet(), SelectionOps.range(list, setOf(paths[1], paths[3])))
        assertEquals(setOf(paths[0], paths[2], paths[4]), SelectionOps.invert(list, setOf(paths[1], paths[3])))
        assertEquals(setOf(paths[1], paths[3]), SelectionOps.sameType(list, setOf(paths[1])))
        assertEquals(setOf(paths[0], paths[2], paths[4]), SelectionOps.sameType(list, setOf(paths[0], paths[2])))
        assertEquals(emptySet<String>(), SelectionOps.range(list, emptySet()))
    }

    @Test
    fun fileKinds() {
        assertEquals(FileKind.FOLDER, FileKinds.of(entry("x.zip", dir = true)))
        assertEquals(FileKind.ARCHIVE, FileKinds.of(entry("Config.ZIP")))
        assertEquals(FileKind.APK, FileKinds.of(entry("a.apk")))
        assertEquals(FileKind.DISK_IMAGE, FileKinds.of(entry("init_boot.img")))
        assertEquals(FileKind.TEXT, FileKinds.of(entry("module.prop")))
        assertEquals(FileKind.TEXT, FileKinds.of(entry("post-fs-data", size = 100)))
        assertEquals(FileKind.OTHER, FileKinds.of(entry("blob", size = 5L * 1024 * 1024)))
        assertEquals(FileKind.OTHER, FileKinds.of(entry("a.weird")))
        assertEquals("", FileKinds.extension(".hidden"))
        assertEquals("", FileKinds.extension("trailing."))
    }

    @Test
    fun systemlessTargets() {
        assertTrue(Systemless.canOverride("/system/etc/hosts"))
        assertTrue(Systemless.canOverride("/vendor/etc/thermal.conf"))
        assertTrue(!Systemless.canOverride("/data/adb/x") && !Systemless.canOverride("/system"))
        assertEquals("/data/adb/modules/cam_file_override/system/etc/hosts", Systemless.target("/system/etc/hosts"))
        assertEquals("/data/adb/modules/cam_file_override/system/vendor/etc/a.conf", Systemless.target("/vendor/etc/a.conf"))
        assertTrue(Systemless.MODULE_PROP.lines().first() == "id=cam_file_override")
        assertTrue(Systemless.MODULE_PROP.endsWith("\n"))
    }

    @Test
    fun appViewPaths() {
        assertEquals(1234 to "/", AppView.parse("/proc/1234/root"))
        assertEquals(1234 to "/data/data", AppView.parse("/proc/1234/root/data/data"))
        assertNull(AppView.parse("/proc/1234/status"))
        assertNull(AppView.parse("/data/proc/1/root"))
        assertEquals("/proc/7/root", AppView.root(7))
    }
}
