package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AttestWriterTest {

    private fun tempDir(): File = Files.createTempDirectory("attest").toFile()

    @Test
    fun writesContentAndLeavesNoTemp() {
        val dir = tempDir()
        val dest = File(dir, "push.json")
        assertTrue(atomicWrite(dest) { it.writeText("hello") })
        assertEquals("hello", dest.readText())
        assertFalse(File(dir, "push.json.tmp").exists())
    }

    @Test
    fun failureLeavesDestUnchangedAndCleansTemp() {
        val dir = tempDir()
        val dest = File(dir, "push.json")
        dest.writeText("old")
        assertFalse(atomicWrite(dest) { throw IOException("boom") })
        assertEquals("old", dest.readText())
        assertFalse(File(dir, "push.json.tmp").exists())
    }
}
