package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RootFsTest {
    private lateinit var fake: FakeFs
    private lateinit var fs: RootFs

    @Before
    fun setUp() {
        fake = FakeFs()
        fs = RootFs(fake)
        fake.dir("/data")
        fake.dir("/data/adb")
    }

    private fun assertErrno(errno: Int, block: () -> Unit) {
        try {
            block()
            fail("expected errno $errno")
        } catch (e: FsException) {
            assertEquals(errno, e.errno)
        }
    }

    private fun write(path: String, text: String, beforeCommit: () -> Unit = {}) =
        fs.writeAtomic(path, text.byteInputStream(), beforeCommit)

    @Test
    fun listDirReportsTypesAndLinks() {
        fake.dir("/d")
        fake.file("/d/a", "hello")
        fake.dir("/d/sub")
        fake.link("/d/toSub", "sub")
        fake.link("/d/broken", "/nowhere")
        fake.link("/d/toFile", "/d/a")

        val byName = fs.listDir("/d").associateBy { it.name }

        assertEquals(FileEntry.TYPE_FILE, byName.getValue("a").type)
        assertEquals(5L, byName.getValue("a").size)
        assertEquals("/d/a", byName.getValue("a").path)
        assertTrue(byName.getValue("sub").isDir)

        val toSub = byName.getValue("toSub")
        assertEquals(FileEntry.TYPE_LINK, toSub.type)
        assertEquals("sub", toSub.linkTarget)
        assertTrue(toSub.isDir)
        assertFalse(toSub.linkBroken)

        assertTrue(byName.getValue("broken").linkBroken)
        assertFalse(byName.getValue("broken").isDir)
        assertFalse(byName.getValue("toFile").isDir)
    }

    @Test
    fun listDirShowsContextAndOwnerButNoContextForLinks() {
        fake.dir("/d")
        fake.file("/d/a").apply {
            uid = 10123
            gid = 1000
            context = "u:object_r:adb_data_file:s0"
        }
        fake.link("/d/l", "/d/a")

        val byName = fs.listDir("/d").associateBy { it.name }

        assertEquals("u0_a123", byName.getValue("a").owner)
        assertEquals("system", byName.getValue("a").group)
        assertEquals("u:object_r:adb_data_file:s0", byName.getValue("a").context)
        assertNull(byName.getValue("l").context)
    }

    @Test
    fun listDirOfFileFails() {
        fake.file("/f")
        assertErrno(Errno.ENOTDIR) { fs.listDir("/f") }
    }

    @Test
    fun statOfMissingIsNullAndRootHasAName() {
        assertNull(fs.stat("/missing"))
        assertEquals("/", fs.stat("/")!!.name)
    }

    @Test
    fun relativePathIsRejected() {
        assertErrno(Errno.EINVAL) { fs.listDir("data") }
    }

    @Test
    fun atomicWriteKeepsMetadata() {
        fake.dir("/data/adb/service.d")
        fake.file("/data/adb/service.d/x.sh", "old", perm = 0x1ED).apply {
            uid = 2000
            gid = 2000
            context = "u:object_r:adb_data_file:s0"
        }
        fake.newFileContext = "u:object_r:system_data_file:s0"

        write("/data/adb/service.d/x.sh", "new")

        val node = fake.node("/data/adb/service.d/x.sh")
        assertEquals("new", fake.text("/data/adb/service.d/x.sh"))
        assertEquals(0x1ED, node.perm)
        assertEquals(2000, node.uid)
        assertEquals(2000, node.gid)
        assertEquals("u:object_r:adb_data_file:s0", node.context)
        assertEquals(listOf("x.sh"), fake.names("/data/adb/service.d"))
    }

    @Test
    fun newFileGets0644() {
        write("/data/adb/new.txt", "hi")
        assertEquals("hi", fake.text("/data/adb/new.txt"))
        assertEquals(0x1A4, fake.node("/data/adb/new.txt").perm)
    }

    @Test
    fun writeThroughSymlinkUpdatesTargetAndKeepsLink() {
        fake.file("/data/adb/real", "old")
        fake.link("/data/adb/l", "real")

        write("/data/adb/l", "new")

        assertTrue(fake.node("/data/adb/l") is FakeFs.Link)
        assertEquals("new", fake.text("/data/adb/real"))
    }

    @Test
    fun failedCommitKeepsOriginalAndCleansTemp() {
        fake.file("/data/adb/a", "old")
        try {
            write("/data/adb/a", "partial") { throw IOException("writer gave up") }
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals("old", fake.text("/data/adb/a"))
        assertEquals(listOf("a"), fake.names("/data/adb"))
    }

    @Test
    fun staleTempFileIsReplaced() {
        fake.file("/data/adb/a", "old")
        fake.file("/data/adb/.a${RootFs.TMP_SUFFIX}", "stale")
        write("/data/adb/a", "new")
        assertEquals("new", fake.text("/data/adb/a"))
        assertEquals(listOf("a"), fake.names("/data/adb"))
    }

    @Test
    fun writeOverDirectoryFails() {
        assertErrno(Errno.EISDIR) { write("/data/adb", "x") }
    }

    @Test
    fun openReadOfDirectoryFails() {
        assertErrno(Errno.EISDIR) { fs.openRead("/data") }
    }

    @Test
    fun renameNeverOverwrites() {
        fake.file("/data/a", "a")
        fake.file("/data/b", "b")
        assertErrno(Errno.EEXIST) { fs.rename("/data/a", "/data/b") }
        fake.link("/data/broken", "/nowhere")
        assertErrno(Errno.EEXIST) { fs.rename("/data/a", "/data/broken") }
        assertEquals("b", fake.text("/data/b"))
    }

    @Test
    fun renameIntoItselfIsRejected() {
        assertErrno(Errno.EINVAL) { fs.rename("/data", "/data/adb/data") }
        assertErrno(Errno.EINVAL) { fs.rename("/", "/x") }
    }

    @Test
    fun renameMoves() {
        fake.file("/data/a", "a")
        fs.rename("/data/a", "/data/adb/b")
        assertEquals("a", fake.text("/data/adb/b"))
        assertNull(fs.stat("/data/a"))
    }

    @Test
    fun createRejectsBadNamesAndExisting() {
        assertErrno(Errno.EINVAL) { fs.mkdir("/") }
        assertErrno(Errno.EEXIST) { fs.mkdir("/data/adb") }
        fs.createFile("/data/adb/f")
        assertErrno(Errno.EEXIST) { fs.createFile("/data/adb/f") }
        assertErrno(Errno.EINVAL) { fs.symlink("", "/data/adb/l") }
    }

    @Test
    fun recursiveChmodDoesNotFollowSymlinks() {
        fake.dir("/outside")
        fake.file("/outside/secret", perm = 0x180) // 0600
        fake.dir("/data/adb/tree")
        fake.file("/data/adb/tree/f")
        fake.link("/data/adb/tree/out", "/outside")

        fs.chmod("/data/adb/tree", 0x1C0, recursive = true) // 0700

        assertEquals(0x1C0, fake.node("/data/adb/tree").perm)
        assertEquals(0x1C0, fake.node("/data/adb/tree/f").perm)
        assertEquals(0x1ED, fake.node("/outside").perm)
        assertEquals(0x180, fake.node("/outside/secret").perm)
    }

    @Test
    fun recursiveChownTouchesLinkButNotTarget() {
        fake.dir("/outside")
        fake.dir("/data/adb/tree")
        fake.link("/data/adb/tree/out", "/outside")

        fs.chown("/data/adb/tree", 2000, 2000, recursive = true)

        assertEquals(2000, fake.node("/data/adb/tree/out").uid)
        assertEquals(0, fake.node("/outside").uid)
    }

    @Test
    fun recursiveContextSkipsLinks() {
        fake.dir("/outside")
        fake.dir("/data/adb/tree")
        fake.file("/data/adb/tree/f")
        fake.link("/data/adb/tree/out", "/outside")

        fs.setContext("/data/adb/tree", "u:object_r:adb_data_file:s0", recursive = true)

        assertEquals("u:object_r:adb_data_file:s0", fake.node("/data/adb/tree/f").context)
        assertEquals("u:object_r:system_file:s0", fake.node("/outside").context)
        assertErrno(Errno.EINVAL) { fs.setContext("/data/adb/tree", " ", recursive = false) }
    }

    @Test
    fun sharedStorageMapsToTheDiskUnderFuse() {
        assertEquals("/data/media/0/Download/SU Kernel", SharedStorage.lower("/storage/emulated/0/Download/SU Kernel"))
        assertEquals("/data/media/10/DCIM", SharedStorage.lower("/storage/emulated/10/DCIM"))
        assertEquals("/data/media/0/Download", SharedStorage.lower("/sdcard/Download"))
        assertEquals("/data/media/0/Download", SharedStorage.lower("/storage/self/primary/Download"))
        // the user's root, app folders (owned by each app, counted in its quota) and other places: no
        assertNull(SharedStorage.lower("/storage/emulated/0"))
        assertNull(SharedStorage.lower("/sdcard"))
        assertNull(SharedStorage.lower("/storage/emulated/0/Android"))
        assertNull(SharedStorage.lower("/storage/emulated/0/Android/data/com.example"))
        assertNull(SharedStorage.lower("/data/adb"))
        assertNull(SharedStorage.lower("/storage/1234-ABCD/DCIM"))
    }

    @Test
    fun repairSharedGivesTheTreeItsParentsOwnerModeAndLabel() {
        // what root's `mkdir -p` (umask 077) and `chown media_rw` used to leave in Download
        fake.dir("/data/media")
        fake.dir("/data/media/0")
        fake.dir("/data/media/0/Download").apply {
            perm = 0x5F8 // 02770
            uid = 10150
            gid = 1023
            context = "u:object_r:media_rw_data_file:s0"
        }
        fake.newFileContext = "u:object_r:media_rw_data_file:s0:c512,c768"
        fake.mkdir("/data/media/0/Download/SU Kernel", 0x1C0)
        fake.mkdir("/data/media/0/Download/SU Kernel/APK", 0x1C0)
        fake.createFile("/data/media/0/Download/SU Kernel/APK/a.apk", 0x1B4) // 0664
        fake.link("/data/media/0/Download/SU Kernel/out", "/data/adb")
        for (p in listOf("/data/media/0/Download/SU Kernel", "/data/media/0/Download/SU Kernel/APK", "/data/media/0/Download/SU Kernel/APK/a.apk")) {
            fake.lchown(p, 1023, 1023)
        }

        fs.repairShared("/storage/emulated/0/Download/SU Kernel")

        for (dir in listOf("/data/media/0/Download/SU Kernel", "/data/media/0/Download/SU Kernel/APK")) {
            val node = fake.node(dir)
            assertEquals(0x5F8, node.perm)
            assertEquals(10150, node.uid)
            assertEquals(1023, node.gid)
            assertEquals("u:object_r:media_rw_data_file:s0", node.context)
        }
        val file = fake.node("/data/media/0/Download/SU Kernel/APK/a.apk")
        assertEquals(0x1B0, file.perm) // 0660: read and write, no execute or setgid
        assertEquals(10150, file.uid)
        assertEquals("u:object_r:media_rw_data_file:s0", file.context)
        // a link is not followed
        assertEquals(0, fake.node("/data/adb").uid)
        assertEquals(0x1ED, fake.node("/data/adb").perm)
    }

    @Test
    fun repairSharedRefusesOtherPlaces() {
        assertErrno(Errno.EINVAL) { fs.repairShared("/data/adb") }
        assertErrno(Errno.EINVAL) { fs.repairShared("/storage/emulated/0/Android/data/com.example") }
    }

    @Test
    fun nonRecursiveChmodLeavesChildren() {
        fake.file("/data/adb/f")
        fs.chmod("/data/adb", 0x1C0, recursive = false)
        assertEquals(0x1C0, fake.node("/data/adb").perm)
        assertEquals(0x1A4, fake.node("/data/adb/f").perm)
    }

    @Test
    fun measureCountsTreeWithoutFollowingLinks() {
        fake.dir("/outside")
        fake.file("/outside/huge", "x".repeat(1000))
        fake.dir("/data/adb/m")
        fake.dir("/data/adb/m/sub")
        fake.file("/data/adb/m/a", "12345")
        fake.file("/data/adb/m/sub/b", "123")
        fake.link("/data/adb/m/out", "/outside")

        assertEquals(listOf(8L, 3L, 1L), fs.measure("/data/adb/m").toList())
        assertEquals(listOf(5L, 1L, 0L), fs.measure("/data/adb/m/a").toList())
    }

    @Test
    fun measureLeavesKernelViewsOutAndStopsWhenCancelled() {
        fake.dir("/proc")
        fake.file("/proc/kcore", "x".repeat(1000)) // claims terabytes on a device
        fake.file("/data/adb/f", "12")
        // "/" counts /data but not what is in /proc (the folder itself still counts)
        assertEquals(listOf(2L, 1L, 3L), fs.measure("/").toList())
        // measured on its own, /proc is walked
        assertEquals(listOf(1000L, 1L, 0L), fs.measure("/proc").toList())
        assertEquals(listOf(0L, 0L, 0L), fs.measure("/data") { true }.toList())
    }

    @Test
    fun onlyRegularFilesAreRead() {
        fake.device("/data/adb/zero")
        fake.link("/data/adb/z", "/data/adb/zero")
        fake.dir("/data/adb/d")
        assertErrno(Errno.EINVAL) { fs.openRead("/data/adb/zero") }
        assertErrno(Errno.EINVAL) { fs.openRead("/data/adb/z") }
        assertErrno(Errno.EISDIR) { fs.openRead("/data/adb/d") }
        assertErrno(Errno.EINVAL) { fs.checksums("/data/adb/zero") }
        assertEquals(FileEntry.TYPE_OTHER, fs.stat("/data/adb/zero")!!.type)
        assertEquals(FileKind.OTHER, FileKinds.of(fs.stat("/data/adb/zero")!!))
    }

    @Test
    fun linksCarryTheirTargetSize() {
        fake.file("/data/adb/big", "x".repeat(300))
        fake.link("/data/adb/l", "big")
        val link = fs.stat("/data/adb/l")!!
        assertEquals(3L, link.size) // the link itself: "big"
        assertEquals(300L, link.contentSize)
        assertEquals(300L, fs.stat("/data/adb/big")!!.contentSize)
    }

    @Test
    fun checksumsStopWhenCancelled() {
        fake.file("/data/adb/abc", "abc")
        try {
            fs.checksums("/data/adb/abc") { true }
            fail("expected cancel")
        } catch (_: OpCancelledException) {
        }
    }

    @Test
    fun checksumsAndRanges() {
        fake.file("/data/adb/abc", "abc")
        assertEquals(
            listOf(
                "900150983cd24fb0d6963f7d28e17f72",
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            fs.checksums("/data/adb/abc"),
        )
        fake.file("/data/adb/r", "0123456789")
        assertEquals("345", String(fs.readRange("/data/adb/r", 3, 3)))
        assertEquals("89", String(fs.readRange("/data/adb/r", 8, 10)))
        assertEquals(0, fs.readRange("/data/adb/r", 50, 4).size)
    }

    @Test
    fun searchByNameAndContent() {
        fake.dir("/data/adb/m")
        fake.dir("/data/adb/m/sub")
        fake.file("/data/adb/m/module.prop", "id=cam")
        fake.file("/data/adb/m/sub/service.sh", "echo needle here")
        fake.file("/data/adb/m/sub/other.sh", "nothing")
        fake.dir("/outside")
        fake.file("/outside/service.sh", "needle")
        fake.link("/data/adb/m/out", "/outside")
        // a match split across the 256 KB read boundary
        fake.file("/data/adb/m/big.bin", "x".repeat(256 * 1024 - 3) + "needle" + "y".repeat(10))

        assertEquals(setOf("service.sh", "other.sh"), fs.search("/data/adb/m", "*.sh", null, 100).map { it.name }.toSet())
        assertEquals(listOf("module.prop"), fs.search("/data/adb/m", "MODULE", null, 100).map { it.name })
        assertEquals(listOf("module.prop"), fs.search("/data/adb/m", "mod?le.prop", null, 100).map { it.name })
        assertEquals(setOf("service.sh", "big.bin"), fs.search("/data/adb/m", "", "needle", 100).map { it.name }.toSet())
        assertEquals(1, fs.search("/data/adb/m", "", null, 1).size)
    }

    @Test
    fun errnoSurvivesBinder() {
        val back = FsException.fromRemote(FsException(Errno.EEXIST, "/data/a").toRemote())!!
        assertEquals(Errno.EEXIST, back.errno)
        assertEquals("errno 17: /data/a", back.message)
        assertNull(FsException.fromRemote(IllegalStateException("plain")))
    }
}
