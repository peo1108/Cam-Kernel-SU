package cam.su.kernel.data.repository

import android.content.SharedPreferences
import android.os.Process
import androidx.core.content.edit
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import kotlinx.coroutines.Dispatchers
import cam.su.kernel.ui.util.getRootShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class FileStamp(val lastModified: Long, val length: Long)

/** Home-screen wallpaper of the Android user that owns [uid]. */
fun systemWallpaperPath(uid: Int): String = "/data/system/users/${uid / 100000}/wallpaper"

fun shouldRecopy(source: FileStamp, cached: FileStamp?): Boolean = cached != source

/** Root file access, split out so the copy logic can be tested without a device. */
interface RootFiles {
    fun isRoot(): Boolean
    fun stamp(path: String): FileStamp?
    fun open(path: String): InputStream
}

object LibsuRootFiles : RootFiles {
    // The app's ksud-backed root shell; libsu's default shell would run plain `su`, which fails
    // when su compatibility is turned off.
    private fun file(path: String) = SuFile(path).apply { shell = getRootShell() }

    override fun isRoot(): Boolean = getRootShell().isRoot

    override fun stamp(path: String): FileStamp? {
        val file = file(path)
        if (!file.isFile) return null
        val length = file.length()
        return if (length > 0) FileStamp(file.lastModified(), length) else null
    }

    override fun open(path: String): InputStream = SuFileInputStream.open(file(path))
}

/** Remembers the source stamp of the cached copy. */
interface StampStore {
    var stamp: FileStamp?
}

class PrefsStampStore(private val prefs: SharedPreferences) : StampStore {
    override var stamp: FileStamp?
        get() {
            val mtime = prefs.getLong(KEY_MTIME, -1L)
            val length = prefs.getLong(KEY_LEN, -1L)
            return if (mtime < 0 || length < 0) null else FileStamp(mtime, length)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_MTIME)
                remove(KEY_LEN)
            } else {
                putLong(KEY_MTIME, value.lastModified)
                putLong(KEY_LEN, value.length)
            }
        }

    private companion object {
        const val KEY_MTIME = "glass_wallpaper_mtime"
        const val KEY_LEN = "glass_wallpaper_len"
    }
}

/**
 * Copies the device wallpaper into app storage through the root shell, since apps
 * cannot read the wallpaper bitmap directly on Android 13+.
 */
class WallpaperRepository(
    private val filesDir: File,
    private val store: StampStore,
    private val root: RootFiles = LibsuRootFiles,
    private val uid: Int = Process.myUid(),
) {
    private val cached = File(filesDir, "glass_wallpaper")

    /** Returns the cached wallpaper file, or null when it cannot be read (no root, live wallpaper). */
    suspend fun refresh(): File? = withContext(Dispatchers.IO) {
        // Serialized: a cold start triggers two refreshes before the first finishes.
        copyLock.withLock {
            runCatching {
                if (!root.isRoot()) return@runCatching null
                val path = systemWallpaperPath(uid)
                val source = root.stamp(path) ?: return@runCatching null
                if (cached.isFile && !shouldRecopy(source, store.stamp)) return@runCatching cached
                val tmp = File.createTempFile("glass_wallpaper", ".tmp", filesDir)
                try {
                    root.open(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // Atomic replace: a good cached copy is never deleted on failure.
                    Files.move(tmp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    tmp.delete()
                }
                store.stamp = source
                cached
            }.getOrNull()
        }
    }

    private companion object {
        val copyLock = Mutex()
    }
}
