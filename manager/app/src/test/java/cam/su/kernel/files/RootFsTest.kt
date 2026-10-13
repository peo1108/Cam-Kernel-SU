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
    fun errnoSurvivesBinder() {
        val back = FsException.fromRemote(FsException(Errno.EEXIST, "/data/a").toRemote())!!
        assertEquals(Errno.EEXIST, back.errno)
        assertEquals("errno 17: /data/a", back.message)
        assertNull(FsException.fromRemote(IllegalStateException("plain")))
    }
}
