package cam.su.kernel.data.repository

import android.content.Context
import androidx.core.content.edit
import cam.su.kernel.camApp
import cam.su.kernel.data.model.HidingAudit
import org.json.JSONObject
import java.io.File

/** A hiding audit with the time it ran. */
data class SavedHidingScan(val time: Long, val audit: HidingAudit)

/**
 * Keeps the last two hiding audits so the page can tell what is new and what got hidden,
 * and the findings the user chose to ignore. [scope] keeps one app's checks (`uid_<uid>`)
 * apart from the device's (empty).
 */
class HidingHistoryRepository(
    private val context: Context = camApp,
    private val scope: String = "",
) {

    private val dir: File
        get() = File(context.filesDir, "hiding_check/history/${scope.ifEmpty { "device" }}")

    private val prefs
        get() = context.getSharedPreferences("hiding_check", Context.MODE_PRIVATE)

    /** The last scan and the one before it. */
    fun load(): Pair<SavedHidingScan?, SavedHidingScan?> = read("last.json") to read("previous.json")

    /** Saves [scan] as the last one; the last one so far becomes the previous. */
    fun save(scan: SavedHidingScan) {
        runCatching {
            dir.mkdirs()
            val last = File(dir, "last.json")
            if (last.exists()) last.copyTo(File(dir, "previous.json"), overwrite = true)
            last.writeText(JSONObject().put("time", scan.time).put("audit", scan.audit.toJson()).toString())
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    var ignored: Set<String>
        get() = prefs.getStringSet("ignored", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet("ignored", value) }

    private fun read(name: String): SavedHidingScan? = runCatching {
        val obj = JSONObject(File(dir, name).readText())
        val audit = HidingAudit.parse(obj.getJSONObject("audit").toString()) ?: return null
        SavedHidingScan(obj.getLong("time"), audit)
    }.getOrNull()
}
