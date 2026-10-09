package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray

enum class ConflictKind { File, Replace, Prop }

/** One entry of `ksud module conflicts`: [path] is a target path, or a prop key for [ConflictKind.Prop]. */
@Immutable
data class ModuleConflict(
    val kind: ConflictKind,
    val path: String,
    val modules: List<String>,
)

fun parseModuleConflicts(json: String): List<ModuleConflict> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val kind = when (obj.optString("kind")) {
            "file" -> ConflictKind.File
            "replace" -> ConflictKind.Replace
            "prop" -> ConflictKind.Prop
            else -> return@mapNotNull null
        }
        val ids = obj.optJSONArray("modules") ?: return@mapNotNull null
        ModuleConflict(
            kind = kind,
            path = obj.optString("path"),
            modules = (0 until ids.length()).map { ids.optString(it) },
        )
    }
}.getOrDefault(emptyList())

fun List<ModuleConflict>.forModule(id: String): List<ModuleConflict> = filter { id in it.modules }

/** Applies the Features page options: detection off hides everything, props can be left out. */
fun List<ModuleConflict>.visible(detection: Boolean, includeProps: Boolean): List<ModuleConflict> = when {
    !detection -> emptyList()
    includeProps -> this
    else -> filter { it.kind != ConflictKind.Prop }
}
