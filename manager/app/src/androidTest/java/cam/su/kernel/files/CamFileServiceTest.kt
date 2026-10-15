package cam.su.kernel.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.random.Random

/**
 * ICamFileService through CamFileClient, the way the Files tab calls it: the root process, the
 * reliable pipes, FsException carried across binder, IFileOpCallback. Works only inside [dir]
 * (/data/local/tmp), created and removed by each test. Skipped when the Manager has no root.
 */
@RunWith(AndroidJUnit4::class)
class CamFileServiceTest {
    private val dir = "/data/local/tmp/cam_androidtest"
    private val data = Random(7).nextBytes(300_000)

    @Before
    fun setUp() = runBlocking {
        val root = try {
            CamFileClient.stat("/") != null
        } catch (e: FsException) {
            if (e.errno != Errno.EACCES) throw e
            false
        }
        assumeTrue("the Manager has no root on this device", root)
        removeDir()
        CamFileClient.mkdir(dir)
    }

    @After
    fun tearDown() = runBlocking { removeDir() }

    private suspend fun removeDir() {
        if (CamFileClient.stat(dir) == null) return
        val done = runOp(FileOp(OpKind.DELETE, listOf(dir)))
        assertTrue("cleanup failed: ${done.error}", done.ok)
    }

    private suspend fun runOp(op: FileOp): CamFileClient.OpEvent.Done =
        withTimeout(30_000) { CamFileClient.startOp(op).events.filterIsInstance<CamFileClient.OpEvent.Done>().first() }

    private fun hex(algorithm: String, bytes: ByteArray) =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun writtenBytesReadBackThroughThePipes() = runBlocking {
        val path = "$dir/blob.bin"
        CamFileClient.write(path) { it.write(data) }
        assertEquals(data.size.toLong(), CamFileClient.stat(path)!!.size)
        assertArrayEquals(data, CamFileClient.readBytes(path, data.size))
        // the hex viewer's seek, from the middle
        assertArrayEquals(data.copyOfRange(123_456, 123_472), CamFileClient.readRange(path, 123_456, 16))
    }

    @Test
    fun checksumsMatchTheJvm() = runBlocking {
        val path = "$dir/sum.bin"
        CamFileClient.write(path) { it.write(data) }
        assertEquals(listOf(hex("MD5", data), hex("SHA-1", data), hex("SHA-256", data)), CamFileClient.checksums(path))
    }

    @Test
    fun listingShowsTypesAndLinks() = runBlocking {
        CamFileClient.mkdir("$dir/sub")
        CamFileClient.createFile("$dir/empty.txt")
        CamFileClient.symlink("$dir/sub", "$dir/link")
        val byName = CamFileClient.listDir(dir).associateBy { it.name }
        assertEquals(setOf("sub", "empty.txt", "link"), byName.keys)
        assertEquals(FileEntry.TYPE_DIR, byName.getValue("sub").type)
        assertEquals(FileEntry.TYPE_FILE, byName.getValue("empty.txt").type)
        val link = byName.getValue("link")
        assertEquals(FileEntry.TYPE_LINK, link.type)
        assertTrue("a link to a folder is a folder", link.isDir)
        assertEquals("$dir/sub", link.linkTarget)
        assertEquals(0, byName.getValue("sub").uid) // made by root
    }

    @Test
    fun renameNeverOverwrites() = runBlocking {
        CamFileClient.write("$dir/a") { it.write("a".toByteArray()) }
        CamFileClient.write("$dir/b") { it.write("b".toByteArray()) }
        try {
            CamFileClient.rename("$dir/a", "$dir/b")
            fail("rename replaced an existing file")
        } catch (e: FsException) {
            assertEquals(Errno.EEXIST, e.errno)
        }
        assertArrayEquals("b".toByteArray(), CamFileClient.readBytes("$dir/b", 16))
    }

    @Test
    fun errnoCrossesTheBinder() = runBlocking {
        assertNull(CamFileClient.stat("$dir/missing"))
        try {
            CamFileClient.listDir("$dir/missing")
            fail("listing a missing folder worked")
        } catch (e: FsException) {
            assertEquals(Errno.ENOENT, e.errno)
        }
    }

    @Test
    fun copyJobReportsThroughItsCallback() = runBlocking {
        CamFileClient.write("$dir/src.bin") { it.write(data) }
        CamFileClient.mkdir("$dir/out")
        val done = runOp(FileOp(OpKind.COPY, listOf("$dir/src.bin"), "$dir/out"))
        assertTrue("copy failed: errno ${done.errno} ${done.error}", done.ok)
        assertEquals(1, done.items)
        val copy = CamFileClient.stat("$dir/out/src.bin")
        assertNotNull(copy)
        assertEquals(hex("SHA-256", data), CamFileClient.checksums("$dir/out/src.bin")[2])
    }
}
