package cam.caesar.vn.files

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingFileTest {

    @Test
    fun sharedStorageMountsMapToStorageEmulated() {
        val tg = "Android/data/org.telegram.messenger/files/Telegram/Telegram Documents/a.txt"
        assertEquals(
            listOf("/storage/emulated/0/$tg", "/mnt/user/0/emulated/0/$tg"),
            IncomingFile.candidates("/mnt/user/0/emulated/0/$tg"),
        )
        assertEquals(
            listOf("/storage/emulated/10/Download/x.log", "/data/media/10/Download/x.log"),
            IncomingFile.candidates("/data/media/10/Download/x.log"),
        )
        assertEquals(
            listOf("/storage/emulated/0/Download/x.log", "/mnt/pass_through/0/emulated/0/Download/x.log"),
            IncomingFile.candidates("/mnt/pass_through/0/emulated/0/Download/x.log"),
        )
    }

    @Test
    fun otherPathsStayAsTheyAre() {
        assertEquals(listOf("/storage/emulated/0/a.txt"), IncomingFile.candidates("/storage/emulated/0/a.txt"))
        assertEquals(
            listOf("/data/user/0/org.telegram.messenger/cache/b.apk"),
            IncomingFile.candidates("/data/user/0/org.telegram.messenger/cache/b.apk"),
        )
    }

    @Test
    fun nothingOnDiskGivesNoCandidate() {
        assertEquals(emptyList<String>(), IncomingFile.candidates("pipe:[12345]"))
        assertEquals(emptyList<String>(), IncomingFile.candidates("anon_inode:[eventfd]"))
        assertEquals(emptyList<String>(), IncomingFile.candidates("/data/user/0/x/cache/gone.txt (deleted)"))
    }

    @Test
    fun safeNameKeepsOnePlainName() {
        assertEquals("a.txt", IncomingFile.safeName("a.txt"))
        assertEquals("passwd", IncomingFile.safeName("../../etc/passwd"))
        assertEquals(".bashrc", IncomingFile.safeName(".bashrc"))
        assertEquals("file", IncomingFile.safeName(".."))
        assertEquals("file", IncomingFile.safeName("dir/"))
        assertEquals("file", IncomingFile.safeName(null))
        assertEquals("file", IncomingFile.safeName("   "))
    }
}
