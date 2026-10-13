package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrashTest {
    private lateinit var fake: FakeFs
    private var clock = 1_000_000L
    private lateinit var trash: Trash

    private val quiet = object : OpListener {
        override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) = Resolution(ConflictChoice.RENAME)
    }

    @Before
    fun setUp() {
        fake = FakeFs()
        fake.dir("/data")
        fake.dir("/data/adb")
        fake.dir("/data/adb/m")
        fake.file("/data/adb/m/service.sh", "echo hi")
        fake.dir("/storage")
        fake.dir("/storage/emulated")
        fake.dir("/storage/emulated/0")
        fake.file("/storage/emulated/0/video.mp4", "x".repeat(100))
        trash = Trash(fake) { clock }
    }

    @Test
    fun deletedFilesGoToTheTrashOfTheirFileSystem() {
        trash.put(listOf("/data/adb/m/service.sh", "/storage/emulated/0/video.mp4"), quiet) { false }
        assertFalse("service.sh" in fake.names("/data/adb/m"))
        assertFalse("video.mp4" in fake.names("/storage/emulated/0"))
        val items = trash.list()
        assertEquals(setOf("/data/adb/m/service.sh", "/storage/emulated/0/video.mp4"), items.map { it.original }.toSet())
        assertEquals(Trash.SHARED_ROOT, items.single { it.name == "video.mp4" }.root)
        assertEquals(Trash.DATA_ROOT, items.single { it.name == "service.sh" }.root)
        assertEquals(100L, items.single { it.name == "video.mp4" }.size)
        assertEquals(Trash.SHARED_ROOT, trash.rootFor("/sdcard/Download/a.zip"))
    }

    @Test
    fun restorePutsItBackEvenIfItsFolderIsGone() {
        trash.put(listOf("/data/adb/m"), quiet) { false }
        assertFalse("m" in fake.names("/data/adb"))
        val item = trash.list().single()
        assertTrue(item.isDir)
        trash.restore(item.dir, quiet) { false }
        assertEquals("echo hi", fake.text("/data/adb/m/service.sh"))
        assertTrue(trash.list().isEmpty())
        assertTrue(fake.names(Trash.DATA_ROOT).isEmpty())
    }

    @Test
    fun purgeAndExpiry() {
        trash.put(listOf("/data/adb/m/service.sh"), quiet) { false }
        trash.put(listOf("/storage/emulated/0/video.mp4"), quiet) { false }
        trash.purge(listOf(trash.list().single { it.name == "service.sh" }.dir), quiet) { false }
        assertEquals(listOf("video.mp4"), trash.list().map { it.name })
        // a week later the rest is gone by itself
        clock += 8 * 24 * 60 * 60 * 1000L
        assertTrue(trash.list().isEmpty())
        assertTrue(fake.names(Trash.SHARED_ROOT).isEmpty())
    }

    @Test
    fun insideTheTrashDeleteIsForGood() {
        assertTrue(trash.isInTrash("/data/adb/cam/trash/1-0/x"))
        assertTrue(trash.isInTrash("/sdcard/.CamTrash/1-0"))
        assertFalse(trash.isInTrash("/data/adb/cam/x"))
        try {
            trash.purge(listOf("/data/adb/m"), quiet) { false }
            throw AssertionError("purged outside the trash")
        } catch (e: FsException) {
            assertEquals(Errno.EINVAL, e.errno)
        }
    }
}
