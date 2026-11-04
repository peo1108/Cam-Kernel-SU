package cam.su.kernel.ui.util.module

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File
import java.util.zip.ZipFile

/** The `id` from a module zip's `module.prop`, or null when the zip has none. */
fun readModuleIdFromZip(file: File): String? = readModulePropFromZip(file)?.id

/** What a module zip says about itself in its `module.prop`. */
@Immutable
data class ModuleProp(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
)

/** null when [text] has no `id`: camd refuses such a zip. Comments and lines without `=` are skipped. */
fun parseModuleProp(text: String): ModuleProp? {
    val values = HashMap<String, String>()
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || !trimmed.contains('=')) continue
        // the first value wins, as with the id before
        values.getOrPut(trimmed.substringBefore('=').trim()) { trimmed.substringAfter('=').trim() }
    }
    val id = values["id"]?.takeIf { it.isNotEmpty() } ?: return null
    return ModuleProp(
        id = id,
        name = values["name"].orEmpty().ifEmpty { id },
        version = values["version"].orEmpty(),
        author = values["author"].orEmpty(),
        description = values["description"].orEmpty(),
    )
}

/** null when [file] is not a readable zip or has no usable `module.prop`. */
fun readModulePropFromZip(file: File): ModuleProp? = runCatching {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("module.prop") ?: return@use null
        parseModuleProp(zip.getInputStream(entry).bufferedReader().use { it.readText() })
    }
}.getOrNull()

/** What a file picker returned, one Uri or many; an item without a Uri is skipped. */
fun Intent.pickedUris(): List<Uri> {
    val clip = clipData ?: return listOfNotNull(data)
    return List(clip.itemCount) { clip.getItemAt(it)?.uri }.filterNotNull()
}
