package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class WallpaperRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRootFiles(
        var root: Boolean = true,
        var stamp: FileStamp? = FileStamp(100, 3),
        var bytes: ByteArray = byteArrayOf(1, 2, 3),
    ) : RootFiles {
        val opened = mutableListOf<String>()
        override fun isRoot() = root
        override fun stamp(path: String) = stamp
        override fun open(path: String): InputStream {
            opened += path
            return ByteArrayInputStream(bytes)
        }
    }

    private class MemoryStampStore : StampStore {
        override var stamp: FileStamp? = null
    }

    private fun repo(root: FakeRootFiles, dir: File = tmp.root, store: StampStore = MemoryStampStore()) =
        WallpaperRepository(dir, store, root, uid = 10234)

    @Test
    fun pathForPrimaryUser() = assertEquals("/data/system/users/0/wallpaper", systemWallpaperPath(10234))

    @Test
    fun pathForSecondaryUser() = assertEquals("/data/system/users/10/wallpaper", systemWallpaperPath(1010234))

    @Test
    fun recopyWhenNoCache() = assertTrue(shouldRecopy(FileStamp(1, 2), null))

    @Test
    fun noRecopyWhenSame() = assertFalse(shouldRecopy(FileStamp(1, 2), FileStamp(1, 2)))

    @Test
    fun recopyWhenChanged() = assertTrue(shouldRecopy(FileStamp(1, 3), FileStamp(1, 2)))

    @Test
    fun refreshReturnsNullWithoutRoot() = runBlocking {
        val root = FakeRootFiles()
        val store = MemoryStampStore()
        assertTrue(repo(root, store = store).refresh() != null)
        root.root = false
        assertNull(repo(root, store = store).refresh())
    }

    @Test
    fun refreshReturnsNullWhenSourceMissing() = runBlocking {
        assertNull(repo(FakeRootFiles(stamp = null)).refresh())
    }

    @Test
    fun refreshCopiesBytesOnFirstRun() = runBlocking {
        val root = FakeRootFiles(bytes = byteArrayOf(9, 8, 7, 6))
        val file = repo(root).refresh()!!
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), file.readBytes())
        assertEquals(listOf("/data/system/users/0/wallpaper"), root.opened)
    }

    @Test
    fun refreshSkipsCopyWhenStampUnchanged() = runBlocking {
        val root = FakeRootFiles()
        val store = MemoryStampStore()
        repo(root, store = store).refresh()
        val second = repo(root, store = store).refresh()
        assertTrue(second != null && second.exists())
        assertEquals(1, root.opened.size)
    }

    @Test
    fun refreshRecopiesWhenCachedFileDeleted() = runBlocking {
        val root = FakeRootFiles()
        val store = MemoryStampStore()
        repo(root, store = store).refresh()!!.delete()
        assertTrue(repo(root, store = store).refresh()!!.exists())
        assertEquals(2, root.opened.size)
    }
}
