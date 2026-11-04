package cam.su.kernel.files

import android.content.SharedPreferences
import androidx.core.content.edit

/** The Files tab's bookmarks, in its preferences; the configuration backup reads and adds to them too. */
object Bookmarks {
    /** The Files tab's preferences file. */
    const val PREFS = "files"
    private const val KEY = "bookmarks"

    val DEFAULTS = listOf(
        "/data/adb/modules",
        "/data/adb/cam",
        "/data/adb/service.d",
        "/data/adb/post-fs-data.d",
        "/data/local/tmp",
    )

    fun load(prefs: SharedPreferences): List<String> {
        if (!prefs.contains(KEY)) return DEFAULTS
        return prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    fun save(prefs: SharedPreferences, list: List<String>) = prefs.edit { putString(KEY, list.joinToString("\n")) }
}
