package cam.su.kernel.hiding

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

/**
 * A module the signed rules (`moduleRisks` in hiding-rules.json) flag: malware, or commands that
 * wreck the device. The idea and the text matching come from KernelSU Next's risk.json; the list
 * is Cam's own.
 */
@Immutable
data class ModuleRisk(
    val level: Level,
    /** exact module ids */
    val ids: List<String>,
    /** word sequences looked for in the module's files, matched after [ModuleRiskScanner.normalize] */
    val patterns: List<String>,
    val reasonVi: String,
    val reasonEn: String,
    /** where the entry came from, shown next to the reason */
    val source: String = "",
) {
    enum class Level { Block, Warn }

    fun reason(locale: Locale = Locale.getDefault()): String =
        if (locale.language == "vi") reasonVi else reasonEn

    fun toJson(): JSONObject = JSONObject()
        .put("level", level.name.lowercase())
        .put("ids", JSONArray(ids))
        .put("patterns", JSONArray(patterns))
        .put("reason", JSONObject().put("vi", reasonVi).put("en", reasonEn))
        .put("source", source)

    companion object {
        /** null when the entry is malformed or of a level this app does not know (newer rules) */
        fun parse(obj: JSONObject): ModuleRisk? = runCatching {
            val level = when (obj.getString("level")) {
                "block" -> Level.Block
                "warn" -> Level.Warn
                else -> return null
            }
            val reason = obj.getJSONObject("reason")
            ModuleRisk(
                level = level,
                ids = obj.optJSONArray("ids")?.let(::strings).orEmpty(),
                patterns = obj.optJSONArray("patterns")?.let(::strings).orEmpty(),
                reasonVi = reason.getString("vi"),
                reasonEn = reason.getString("en"),
                source = obj.optString("source"),
            ).takeIf { it.ids.isNotEmpty() || it.patterns.isNotEmpty() }
        }.getOrNull()

        fun parseList(array: JSONArray?): List<ModuleRisk> =
            if (array == null) emptyList() else List(array.length()) { parse(array.getJSONObject(it)) }.filterNotNull()

        private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
    }
}

/** A [risk] found in a module, and in which file (or "module.prop") it was found. */
@Immutable
data class ModuleRiskHit(val risk: ModuleRisk, val where: String)

/**
 * Matches modules against [ModuleRisk] entries. Text is compared as words: [normalize] keeps
 * letters and digits only, so "Zygisk-Floating_Menu" matches the pattern "zygisk floating menu",
 * while the same words apart from each other do not.
 */
class ModuleRiskScanner(risks: List<ModuleRisk>) {

    // block entries first: a module both blocked and warned about is blocked
    private val risks = risks.sortedBy { it.level }
    private val compiled = this.risks.map { risk ->
        risk to risk.patterns.map(::tokens).filter { it.isNotEmpty() }
    }
    private val ids = this.risks.flatMap { risk -> risk.ids.map { it to risk } }.toMap()

    val isEmpty: Boolean get() = risks.isEmpty()

    /** First entry whose pattern occurs in [text]. */
    fun matchText(text: String): ModuleRisk? {
        val words = tokens(text)
        if (words.isEmpty()) return null
        return compiled.firstOrNull { (_, patterns) -> patterns.any { words.containsRun(it) } }?.first
    }

    /** An installed module, by what its module.prop says (its files are not read). */
    fun matchModule(id: String, name: String, author: String): ModuleRiskHit? {
        ids[id]?.let { return ModuleRiskHit(it, PROP) }
        return matchText("$id\n$name\n$author")?.let { ModuleRiskHit(it, PROP) }
    }

    /**
     * A module zip: the id in module.prop, then every file's text (binaries too, read lossily),
     * at most [MAX_ENTRY_BYTES] of each and [MAX_TOTAL_BYTES] in all. null when nothing matches or
     * the zip cannot be read (camd reports a broken zip itself).
     */
    fun scanZip(zip: File): ModuleRiskHit? {
        if (isEmpty) return null
        return runCatching {
            ZipFile(zip).use { archive ->
                archive.getEntry(PROP)?.let { entry ->
                    val prop = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                    propId(prop)?.let { id -> ids[id]?.let { return ModuleRiskHit(it, PROP) } }
                }
                var budget = MAX_TOTAL_BYTES
                for (entry in archive.entries()) {
                    if (entry.isDirectory || budget <= 0) continue
                    val limit = minOf(MAX_ENTRY_BYTES, budget)
                    val bytes = archive.getInputStream(entry).use { it.readNBytesCompat(limit) }
                    budget -= bytes.size
                    matchText(String(bytes, Charsets.UTF_8))?.let { return ModuleRiskHit(it, entry.name) }
                }
                null
            }
        }.getOrNull()
    }

    companion object {
        const val PROP = "module.prop"
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

        /** NFKC, lower case, every character that is not a letter or digit becomes a space. */
        fun normalize(text: String): String {
            val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            val out = StringBuilder(folded.length)
            var i = 0
            while (i < folded.length) {
                val cp = folded.codePointAt(i)
                if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp) else out.append(' ')
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

        private fun propId(prop: String): String? = prop.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim() == "id" && it.contains('=') }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

        private fun List<String>.containsRun(run: List<String>): Boolean {
            if (run.size > size) return false
            for (start in 0..size - run.size) {
                if (this[start] != run[0]) continue
                if ((1 until run.size).all { this[start + it] == run[it] }) return true
            }
            return false
        }

        // InputStream.readNBytes is API 33; the app runs from API 31
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var left = limit
            while (left > 0) {
                val n = read(buffer, 0, minOf(buffer.size, left))
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
            return out.toByteArray()
        }
    }
}
