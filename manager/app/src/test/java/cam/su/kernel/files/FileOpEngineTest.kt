package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FileOpEngineTest {
    private lateinit var fake: FakeFs
    private val conflicts = mutableListOf<String>()
    private var answer = Resolution(ConflictChoice.SKIP)
    private var lastProgress = 0L to 0L
    private var cancelWhen: () -> Boolean = { false }

    private val listener = object : OpListener {
        override fun onProgress(done: Long, total: Long, current: String) {
            lastProgress = done to total
        }

        override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean): Resolution {
            conflicts += dst
            return answer
        }
    }

    @Before
    fun setUp() {
        fake = FakeFs()
        fake.dir("/data")
        fake.dir("/data/src")
        fake.dir("/data/dst")
        fake.dir("/sdcard")
    }

    private fun run(kind: OpKind, vararg sources: String, dest: String? = null, preserve: Boolean = true) =
        FileOpEngine(fake, listener) { cancelWhen() }.run(FileOp(kind, sources.toList(), dest, preserve))

    private fun assertErrno(errno: Int, block: () -> Unit) {
        try {
            block()
            fail("expected errno $errno")
        } catch (e: FsException) {
            assertEquals(errno, e.errno)
        }
    }

    private fun exists(path: String) = runCatching { fake.lstat(path) }.isSuccess

    @Test
    fun copiesTreeWithModesOwnersLabelsAndLinks() {
        fake.dir("/outside")
        fake.dir("/data/src/m")
        fake.file("/data/src/m/run.sh", "echo hi", perm = 0x1ED).apply {
            uid = 2000
            gid = 2000
            context = "u:object_r:adb_data_file:s0"
        }
        fake.link("/data/src/m/out", "/outside")
        fake.newFileContext = "u:object_r:system_data_file:s0"

        val result = run(OpKind.COPY, "/data/src/m", dest = "/data/dst")

        assertEquals("echo hi", fake.text("/data/dst/m/run.sh"))
        val copied = fake.node("/data/dst/m/run.sh")
        assertEquals(0x1ED, copied.perm)
        assertEquals(2000, copied.uid)
        assertEquals("u:object_r:adb_data_file:s0", copied.context)
        assertEquals("/outside", fake.readlink("/data/dst/m/out"))
        assertEquals(3, result.items)
        assertEquals(listOf("m"), fake.names("/data/src")) // source untouched
        assertEquals(7L to 7L, lastProgress)
    }

    @Test
    fun withoutPreserveKeepsModeButNotOwnerOrLabel() {
        fake.file("/data/src/a", "x", perm = 0x1ED).apply {
            uid = 2000
            context = "u:object_r:adb_data_file:s0"
        }
        fake.newFileContext = "u:object_r:media_rw_data_file:s0"

        run(OpKind.COPY, "/data/src/a", dest = "/data/dst", preserve = false)

        val copied = fake.node("/data/dst/a")
        assertEquals(0x1ED, copied.perm)
        assertEquals(0, copied.uid)
        assertEquals("u:object_r:media_rw_data_file:s0", copied.context)
    }

    @Test
    fun copyIntoSelfRejected() {
        fake.dir("/data/src/m")
        assertErrno(Errno.EINVAL) { run(OpKind.COPY, "/data/src/m", dest = "/data/src/m") }
        fake.dir("/data/src/m/inner")
        assertErrno(Errno.EINVAL) { run(OpKind.MOVE, "/data/src/m", dest = "/data/src/m/inner") }
        // through a symlink to inside the source
        fake.link("/data/dst/sneaky", "/data/src/m/inner")
        assertErrno(Errno.EINVAL) { run(OpKind.COPY, "/data/src/m", dest = "/data/dst/sneaky") }
    }

    @Test
    fun copyIntoSameFolderMakesNewName() {
        fake.file("/data/src/a.txt", "1")
        fake.file("/data/src/a (1).txt", "2")
        run(OpKind.COPY, "/data/src/a.txt", dest = "/data/src")
        assertEquals("1", fake.text("/data/src/a (2).txt"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun conflictSkipRenameOverwrite() {
        fake.file("/data/src/a", "new")
        fake.file("/data/dst/a", "old")

        answer = Resolution(ConflictChoice.SKIP)
        assertEquals(1, run(OpKind.COPY, "/data/src/a", dest = "/data/dst").skipped)
        assertEquals("old", fake.text("/data/dst/a"))

        answer = Resolution(ConflictChoice.RENAME)
        run(OpKind.COPY, "/data/src/a", dest = "/data/dst")
        assertEquals("new", fake.text("/data/dst/a (1)"))

        answer = Resolution(ConflictChoice.OVERWRITE)
        run(OpKind.COPY, "/data/src/a", dest = "/data/dst")
        assertEquals("new", fake.text("/data/dst/a"))
        assertEquals(3, conflicts.size)
    }

    @Test
    fun applyAllAsksOnce() {
        fake.dir("/data/src/m")
        fake.dir("/data/dst/m")
        for (n in listOf("a", "b", "c")) {
            fake.file("/data/src/m/$n", "new")
            fake.file("/data/dst/m/$n", "old")
        }
        answer = Resolution(ConflictChoice.OVERWRITE, applyAll = true)

        run(OpKind.COPY, "/data/src/m", dest = "/data/dst")

        // one for the folder (merge), one for the files
        assertEquals(listOf("/data/dst/m", "/data/dst/m/a"), conflicts)
        assertEquals("new", fake.text("/data/dst/m/c"))
    }

    @Test
    fun overwriteFolderWithFile() {
        fake.file("/data/src/x", "file")
        fake.dir("/data/dst/x")
        fake.file("/data/dst/x/inner")
        answer = Resolution(ConflictChoice.OVERWRITE)

        run(OpKind.COPY, "/data/src/x", dest = "/data/dst")

        assertEquals("file", fake.text("/data/dst/x"))
    }

    @Test
    fun deleteDoesNotFollowSymlink() {
        fake.dir("/outside")
        fake.file("/outside/keep", "precious")
        fake.dir("/data/src/tree")
        fake.dir("/data/src/tree/deep")
        fake.link("/data/src/tree/deep/out", "/outside")
        fake.link("/data/src/tree/file", "/outside/keep")

        val result = run(OpKind.DELETE, "/data/src/tree")

        assertFalse(exists("/data/src/tree"))
        assertEquals("precious", fake.text("/outside/keep"))
        assertEquals(4, result.items)
        assertEquals(4L to 4L, lastProgress)
    }

    @Test
    fun topLevelFoldersAreProtected() {
        assertErrno(Errno.EPERM) { run(OpKind.DELETE, "/data") }
        assertErrno(Errno.EPERM) { run(OpKind.DELETE, "/") }
        assertErrno(Errno.EPERM) { run(OpKind.MOVE, "/sdcard", dest = "/data/dst") }
        assertTrue(exists("/data/src"))
        // copying one is fine
        fake.file("/sdcard/a")
        run(OpKind.COPY, "/sdcard", dest = "/data/dst")
        assertTrue(exists("/data/dst/sdcard/a"))
    }

    @Test
    fun moveRenamesOnSameFileSystem() {
        fake.dir("/data/src/m")
        fake.file("/data/src/m/a", "abc")
        val node = fake.node("/data/src/m")

        run(OpKind.MOVE, "/data/src/m", dest = "/data/dst")

        assertTrue(fake.node("/data/dst/m") === node) // same node: renamed, not copied
        assertFalse(exists("/data/src/m"))
        assertEquals(3L to 3L, lastProgress)
    }

    @Test
    fun moveFallsBackToCopyOnExdev() {
        fake.mounts = listOf("/sdcard")
        fake.dir("/data/src/m")
        fake.file("/data/src/m/a", "abc")
        fake.link("/data/src/m/l", "a")

        run(OpKind.MOVE, "/data/src/m", dest = "/sdcard")

        assertEquals("abc", fake.text("/sdcard/m/a"))
        assertEquals("a", fake.readlink("/sdcard/m/l"))
        assertFalse(exists("/data/src/m"))
    }

    @Test
    fun failedCopyAcrossFileSystemsKeepsSource() {
        fake.mounts = listOf("/sdcard")
        fake.dir("/data/src/m")
        fake.file("/data/src/m/a", "abcdef")
        fake.failRead = setOf("/data/src/m/a")

        try {
            run(OpKind.MOVE, "/data/src/m", dest = "/sdcard")
            fail("expected FsException")
        } catch (e: FsException) {
            assertEquals(Errno.EIO, e.errno)
        }

        assertEquals("abcdef", fake.text("/data/src/m/a"))
        assertTrue(fake.names("/sdcard/m").none { it.endsWith(RootFs.TMP_SUFFIX) })
        assertFalse(exists("/sdcard/m/a"))
    }

    @Test
    fun moveMergesFolders() {
        fake.dir("/data/src/x")
        fake.file("/data/src/x/one", "1")
        fake.dir("/data/dst/x")
        fake.file("/data/dst/x/two", "2")
        answer = Resolution(ConflictChoice.OVERWRITE)

        run(OpKind.MOVE, "/data/src/x", dest = "/data/dst")

        assertEquals(listOf("two", "one"), fake.names("/data/dst/x"))
        assertFalse(exists("/data/src/x"))
    }

    @Test
    fun moveMergeKeepsSkippedFilesInSource() {
        fake.dir("/data/src/x")
        fake.file("/data/src/x/same", "new")
        fake.file("/data/src/x/other", "o")
        fake.dir("/data/dst/x")
        fake.file("/data/dst/x/same", "old")
        var asked = 0
        val engine = FileOpEngine(fake, object : OpListener {
            override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) =
                Resolution(if (asked++ == 0) ConflictChoice.OVERWRITE else ConflictChoice.SKIP)
        })

        val result = engine.run(FileOp(OpKind.MOVE, listOf("/data/src/x"), "/data/dst"))

        assertEquals(1, result.skipped)
        assertEquals(listOf("same"), fake.names("/data/src/x"))
        assertEquals("old", fake.text("/data/dst/x/same"))
        assertEquals("o", fake.text("/data/dst/x/other"))
    }

    @Test
    fun cancelCleansPartialFile() {
        fake.file("/data/src/big", "x".repeat(1024 * 1024))
        var seen = 0
        val engine = FileOpEngine(fake, object : OpListener {
            override fun onProgress(done: Long, total: Long, current: String) {
                if (done > 0) seen++
            }

            override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) =
                Resolution(ConflictChoice.SKIP)
        }) { seen > 0 }

        try {
            engine.run(FileOp(OpKind.COPY, listOf("/data/src/big"), "/data/dst"))
            fail("expected cancel")
        } catch (_: OpCancelledException) {
        }

        assertTrue(fake.names("/data/dst").isEmpty())
        assertNotNull(fake.node("/data/src/big"))
    }

    @Test
    fun uniqueNameKeepsExtensionForFilesOnly() {
        fake.file("/data/dst/a.tar.gz")
        fake.dir("/data/dst/v1.2")
        val engine = FileOpEngine(fake, listener)
        assertEquals("/data/dst/a.tar (1).gz", engine.uniqueName("/data/dst/a.tar.gz"))
        assertEquals("/data/dst/v1.2 (1)", engine.uniqueName("/data/dst/v1.2"))
        assertEquals("/data/dst/.hidden (1)", engine.uniqueName("/data/dst/.hidden"))
    }
}
