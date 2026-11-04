package cam.su.kernel.backup

import androidx.compose.runtime.Immutable
import cam.su.kernel.hiding.ModuleRisk
import cam.su.kernel.hiding.ModuleRiskScanner
import org.json.JSONObject

/**
 * The modules backup (`camd module export`): installed modules in one tar with a
 * `cam-modules.json` manifest. Restoring stages them as pending updates, applied on the next boot.
 */
@Immutable
data class ArchiveModule(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val enabled: Boolean,
    /** version installed now; null when the module is not installed */
    val installed: String? = null,
    /** on the dangerous module list */
    val risk: ModuleRisk? = null,
) {
    val label: String get() = name.ifBlank { id }
}

@Immutable
data class ArchiveImport(val imported: List<String>, val failed: Map<String, String>)

object ModuleArchive {
    const val MIME = "application/x-tar"

    /** `camd module export` stdout (the manifest) or `camd module import --list` stdout. */
    fun parseModules(json: String, risks: ModuleRiskScanner? = null): List<ArchiveModule> {
        val modules = JSONObject(json).getJSONArray("modules")
        return List(modules.length()) { i ->
            val m = modules.getJSONObject(i)
            val id = m.getString("id")
            val name = m.optString("name")
            val author = m.optString("author")
            ArchiveModule(
                id = id,
                name = name,
                author = author,
                version = m.optString("version"),
                enabled = m.optBoolean("enabled", true),
                installed = if (m.isNull("installed")) null else m.optString("installed"),
                risk = risks?.matchModule(id, name, author)?.risk,
            )
        }
    }

    fun parseImport(json: String): ArchiveImport {
        val obj = JSONObject(json)
        val imported = obj.getJSONArray("imported")
        val failed = obj.getJSONArray("failed")
        return ArchiveImport(
            imported = List(imported.length()) { imported.getString(it) },
            failed = (0 until failed.length()).associate {
                failed.getJSONObject(it).let { f -> f.getString("id") to f.optString("error") }
            },
        )
    }

    /** What a restore may bring back: a module the rules block is never offered while blocking is on. */
    fun restorable(module: ArchiveModule, blockRisks: Boolean): Boolean =
        !(blockRisks && module.risk?.level == ModuleRisk.Level.Block)
}
