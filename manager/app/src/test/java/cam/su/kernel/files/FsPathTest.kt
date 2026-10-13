package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsPathTest {

    @Test
    fun normalizes() {
        assertEquals("/", FsPath.normalize("/"))
        assertEquals("/", FsPath.normalize("//"))
        assertEquals("/data/adb", FsPath.normalize("/data//adb/"))
        assertEquals("/data", FsPath.normalize("/data/./adb/.."))
        assertEquals("/", FsPath.normalize("/../.."))
    }

    @Test(expected = FsException::class)
    fun rejectsRelative() {
        FsPath.normalize("data/adb")
    }

    @Test(expected = FsException::class)
    fun rejectsNul() {
        FsPath.normalize("/data\u0000x")
    }

    @Test
    fun splitsAndJoins() {
        assertEquals("/", FsPath.parent("/data"))
        assertEquals("/", FsPath.parent("/"))
        assertEquals("/data", FsPath.parent("/data/adb"))
        assertEquals("adb", FsPath.name("/data/adb"))
        assertEquals("/data", FsPath.child("/", "data"))
        assertEquals("/data/adb", FsPath.child("/data", "adb"))
    }

    @Test
    fun validatesNames() {
        assertTrue(FsPath.isValidName("module.prop"))
        assertTrue(FsPath.isValidName(".hidden"))
        assertFalse(FsPath.isValidName(""))
        assertFalse(FsPath.isValidName("."))
        assertFalse(FsPath.isValidName(".."))
        assertFalse(FsPath.isValidName("a/b"))
        assertFalse(FsPath.isValidName("x".repeat(256)))
        assertFalse(FsPath.isValidName("é".repeat(128))) // 256 bytes in UTF-8
    }

    @Test
    fun containment() {
        assertTrue(FsPath.isSameOrInside("/data", "/data"))
        assertTrue(FsPath.isSameOrInside("/data/adb", "/data"))
        assertFalse(FsPath.isSameOrInside("/database", "/data"))
        assertTrue(FsPath.isSameOrInside("/anything", "/"))
    }
}
