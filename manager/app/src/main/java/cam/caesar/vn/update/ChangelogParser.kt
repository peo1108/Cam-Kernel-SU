package cam.su.kernel.update

/** One `## <version> - <date>` entry of CHANGELOG.md. */
data class ChangelogEntry(val version: String, val date: String, val body: String)

private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

/** major.minor.patch of a plain release name, or null for dev builds ("3.0.0-5-gabc"), hashes and "v3.0.0". */
fun parseSemver(version: String): Triple<Int, Int, Int>? =
    SEMVER.matchEntire(version)?.destructured?.let { (major, minor, patch) ->
        Triple(major.toInt(), minor.toInt(), patch.toInt())
    }

private val semverOrder = compareBy<Triple<Int, Int, Int>>({ it.first }, { it.second }, { it.third })

/** Reads the CHANGELOG.md packed into the APK (see copyChangelog in app/build.gradle.kts). */
object ChangelogParser {
    const val ASSET_PATH = "changelog/CHANGELOG.md"

    private val heading = Regex("""^## (\d+\.\d+\.\d+)\s+-\s+(\S+)""")

    /** Entries in file order; CRLF and LF give the same result. */
    fun parse(markdown: String): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var version: String? = null
        var date = ""
        val body = mutableListOf<String>()

        fun flush() {
            version?.let { entries += ChangelogEntry(it, date, body.joinToString("\n").trim('\n')) }
            body.clear()
        }

        for (line in markdown.replace("\r\n", "\n").split("\n")) {
            val match = heading.find(line)
            if (match != null) {
                flush()
                version = match.groupValues[1]
                date = match.groupValues[2]
            } else if (line.startsWith("## ")) {
                flush()
                version = null
            } else if (version != null) {
                body += line
            }
        }
        flush()
        return entries
    }

    /** Entries with lastSeen < version <= current, newest first; empty when current is not a plain release. */
    fun entriesNewerThan(entries: List<ChangelogEntry>, lastSeen: String?, current: String): List<ChangelogEntry> {
        val upper = parseSemver(current) ?: return emptyList()
        val lower = lastSeen?.let(::parseSemver)
        return entries
            .mapNotNull { entry -> parseSemver(entry.version)?.let { it to entry } }
            .filter { (v, _) ->
                semverOrder.compare(v, upper) <= 0 && (lower == null || semverOrder.compare(v, lower) > 0)
            }
            .sortedWith { a, b -> semverOrder.compare(b.first, a.first) }
            .map { it.second }
    }
}
