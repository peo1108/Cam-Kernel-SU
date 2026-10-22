package cam.su.kernel.hiding

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.ui.util.getSystemProperty
import java.io.File

/**
 * What an app without root can see, checked from inside such an app: [HidingProbeService]
 * runs it in an isolated process, which KernelSU always unmounts modules for. The checks
 * follow the app side probes of Duck Detector; what they look for comes from [HidingRules].
 * Files are read with raw syscalls ([NativeProbe]) when the library loads, and compared
 * with what libc answers, which a hiding module may have hooked.
 */
object AppViewProbe {

    private val PARTITIONS = listOf("/system", "/vendor", "/product", "/system_ext", "/odm")

    /** Root tooling's folders: a mount or a mapped file in them counts whatever the rules say, as camd's ROOT_PREFIXES. */
    private val ROOT_FOLDERS = listOf("/data/adb", "/debug_ramdisk")

    private const val MOUNTINFO = "/proc/self/mountinfo"

    /** One mount: the item shown, and the text naming the module behind it. */
    data class SeenMount(val item: String, val origin: String)

    fun run(rules: HidingRules): HidingAudit {
        val native = NativeProbe.available
        val mountinfo = rawOrLibc(MOUNTINFO)
        val mounts = mounts(mountinfo, rules)
        val mapped = maps(rawOrLibc("/proc/self/maps"), rules)
        val su = rules.suPaths.filter(::visible)
        val props = props(rules, ::getSystemProperty)
        val selinux = selinux(rawOrLibc("/sys/fs/selinux/enforce"))
        val hooked = if (native) hooked(rules) else emptyList()
        val timed = AttrTiming.evaluate(NativeProbe.attrTiming()) as? AttrTiming.Measured

        val findings = listOfNotNull(
            finding("appMounts", mounts.map { it.item }, "kernelUmount", HidingAudit.moduleIds(mounts.map { it.origin })),
            finding("appMaps", mapped, null, HidingAudit.moduleIds(mapped)),
            finding("appSu", su, null),
            finding("appProps", props, "hideBootloader"),
            finding("appSelinux", selinux, null),
            finding("appHooked", hooked, null),
            finding("appAttrTiming", if (timed?.leaks == true) listOf(timed.describe()) else emptyList(), null),
        )
        return HidingAudit(
            findings = findings,
            fixable = findings.count { it.fix != null },
            stats = mapOf(
                "appView" to 1,
                "appNative" to if (native) 1 else 0,
                "appMounts" to mounts.size,
                "appAttrTiming" to if (timed != null) 1 else 0,
            ) + listOfNotNull(timed?.let { "appAttrGapNs" to it.gapMedianNs.toInt() }),
        )
    }

    /** Mounts that come from root tooling: its mount sources, its folders, or modules over a partition. */
    fun mounts(mountinfo: String, rules: HidingRules): List<SeenMount> = mountinfo.lineSequence().mapNotNull { line ->
        val sep = line.indexOf(" - ")
        if (sep < 0) return@mapNotNull null
        val left = line.substring(0, sep).split(' ')
        val right = line.substring(sep + 3).split(' ')
        if (left.size < 6 || right.size < 2) return@mapNotNull null
        val root = left[3]
        val point = left[4]
        val type = right[0]
        val source = right[1]
        val options = right.getOrElse(2) { "" }
        val onPartition = PARTITIONS.any { point == it || point.startsWith("$it/") }
        // a bind from the data partition shows its root as /adb/...
        val suspicious = source in rules.mountSources ||
                root.startsWith("/adb/") ||
                listOf(root, point, options).any { text -> ROOT_FOLDERS.any { it in text } } ||
                (onPartition && type == "overlay") ||
                (onPartition && type == "tmpfs" && point != "/system")
        if (suspicious) SeenMount("$point ($type, $source)", "$root $options") else null
    }.distinctBy { it.item }.toList()

    /** Mapped paths from root or hook tooling, matched and named as camd's mapped_path does. */
    fun maps(maps: String, rules: HidingRules): List<String> = maps.lineSequence().mapNotNull { line ->
        val path = mappedPath(line) ?: return@mapNotNull null
        // lowercase() is Unicode, as apps that look for root lower case (the Kelvin sign is a k)
        val lower = withoutInstallIds(path.lowercase())
        path.takeIf { inRootFolder(path) || rules.mapMarkers.any { it in lower } }
    }.distinct().toList()

    private fun inRootFolder(path: String): Boolean {
        val start = fileStart(path) ?: return false
        return ROOT_FOLDERS.any { path.startsWith(it, start) && path.startsWith("/", start + it.length) }
    }

    // address perms offset dev inode path: the path is the rest of the line, spaces and all
    // ([anon:dalvik-main space]), without " (deleted)"
    private fun mappedPath(line: String): String? {
        var at = 0
        repeat(5) {
            while (at < line.length && line[at].isWhitespace()) at++
            while (at < line.length && !line[at].isWhitespace()) at++
        }
        return line.substring(at).trim().removeSuffix(" (deleted)").ifEmpty { null }
    }

    // Names ART gives memory it fills from a file: the file follows right after, or after
    // EXTRACTED_FROM. Android 8 to 10 name it in ashmem.
    private val ART_NAMES = listOf("[anon:dalvik-", "/dev/ashmem/dalvik-")
    private const val EXTRACTED_FROM = " extracted in memory from "

    // Where the file a mapping comes from starts in its name: at 0 for a file, or in an
    // ART_NAMES name ([anon:dalvik-/data/app/.../base.art], [anon:dalvik-classes.dex
    // extracted in memory from /data/app/...]). camd's file_start is the same.
    private fun fileStart(name: String): Int? {
        for (prefix in ART_NAMES) {
            if (!name.startsWith(prefix)) continue
            if (name.startsWith("/", prefix.length)) return prefix.length
            val from = name.indexOf(EXTRACTED_FROM, prefix.length)
            return if (from < 0) null else from + EXTRACTED_FROM.length
        }
        return if (name.startsWith('/')) 0 else null
    }

    // /data/app/~~<id>==/<pkg>-<id>==/base.apk -> /data/app/<pkg>/base.apk, also without ~~
    // (Android 8 to 10), under /mnt/expand/<uuid>/app, and for the file in an ART name
    // (fileStart): the random ids can hold a marker (WebView's -...EdXpOQh3UA== reads as
    // EdXposed, -aRiRuQ3-... as Riru). Only a file in those install folders is cut, so an id
    // shaped name elsewhere keeps its marker, and a path with nothing cut comes back as it is.
    // camd's without_install_ids (hiding_audit.rs) does the same; both tests read
    // install-id-cases.json.
    internal fun withoutInstallIds(path: String): String {
        val cuts = installIds(path, fileStart(path) ?: return path)
        if (cuts.isEmpty()) return path
        val out = StringBuilder(path.length)
        var copied = 0
        for (cut in cuts) {
            out.append(path, copied, cut.first)
            copied = cut.last + 1
        }
        return out.append(path, copied, path.length).toString()
    }

    // Where the random ids are when path has an install folder at start: ~~<id>/, and the
    // -<id> after the package
    private fun installIds(path: String, start: Int): List<IntRange> {
        var at = when {
            path.startsWith("/data/app/", start) -> start + "/data/app/".length
            path.startsWith("/mnt/expand/", start) -> {
                val end = path.indexOf('/', start + "/mnt/expand/".length)
                if (end < 0 || !path.startsWith("/app/", end)) return emptyList()
                end + "/app/".length
            }
            else -> return emptyList()
        }
        val cuts = ArrayList<IntRange>(2)
        var end = path.indexOf('/', at)
        if (end >= 0 && path.startsWith("~~", at) && isInstallId(path, at + 2, end)) {
            cuts += at..end
            at = end + 1
            end = path.indexOf('/', at)
        }
        if (end < 0) end = path.length
        val dash = end - INSTALL_ID_LENGTH - 1
        if (dash >= at && path[dash] == '-' && isInstallId(path, dash + 1, end)) cuts += dash until end
        return cuts
    }

    private const val INSTALL_ID_LENGTH = 24

    // 16 random bytes in URL-safe base64 from `from` to `to`: 22 characters and ==. They can
    // hold a '-', so an id is found by its length, not at the last '-'.
    private fun isInstallId(s: String, from: Int, to: Int): Boolean =
        to - from == INSTALL_ID_LENGTH && s.startsWith("==", to - 2) && (from until to - 2).all {
            val c = s[it]
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
        }

    fun props(rules: HidingRules, get: (String) -> String): List<String> = rules.safeProps.mapNotNull { (name, safe) ->
        val value = get(name)
        if (value.isEmpty() || value == safe) null else "$name=$value"
    } + get("ro.build.tags").let { if ("test-keys" in it) listOf("ro.build.tags=$it") else emptyList() }

    fun selinux(enforce: String): List<String> =
        if (enforce.trim() == "0") listOf("/sys/fs/selinux/enforce=0") else emptyList()

    /**
     * Where libc and the kernel disagree: mountinfo lines libc does not return, and su
     * paths libc says are missing while the kernel finds them. Maps are left out: they
     * change between two reads on their own.
     */
    fun hookedMounts(raw: String, libc: String): List<String> {
        // both reads decode their bytes with text(), so the same bytes give the same lines
        val seen = libc.lines().toSet()
        val hidden = raw.lines().filter { it.isNotBlank() && it !in seen }
        return if (hidden.isEmpty()) emptyList() else listOf("$MOUNTINFO: ${hidden.size} hidden from libc") +
                hidden.take(3).map { "  ${it.substringAfter(" - ", it)}" }
    }

    private fun hooked(rules: HidingRules): List<String> {
        val raw = rawRead(MOUNTINFO)
        val mounts = if (raw == null) emptyList() else hookedMounts(raw, libcRead(MOUNTINFO))
        val su = rules.suPaths.filter { path ->
            val kernel = NativeProbe.access(path)
            (kernel == 0 || kernel == OsConstants.EACCES) && !visible(path)
        }.map { "$it: hidden from libc" }
        return mounts + su
    }

    /** A path exists when stat works, or is refused (EACCES) rather than missing. Goes through libc. */
    private fun visible(path: String): Boolean = try {
        Os.stat(path)
        true
    } catch (e: ErrnoException) {
        e.errno == OsConstants.EACCES
    }

    /**
     * Bytes read from a proc file as UTF-8 text, a byte that is not UTF-8 turned into U+FFFD:
     * the kernel prints file names as they are, and a Kelvin sign (U+212A) standing for the
     * k of libmagisk.so must reach [maps] whole.
     */
    internal fun text(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

    private fun rawRead(path: String): String? = NativeProbe.read(path)?.let(::text)

    private fun libcRead(path: String): String = runCatching { text(File(path).readBytes()) }.getOrDefault("")

    private fun rawOrLibc(path: String): String = rawRead(path) ?: libcRead(path)

    private fun finding(id: String, items: List<String>, fix: String?, modules: List<String> = emptyList()) =
        if (items.isEmpty()) {
            null
        } else {
            HidingAudit.Finding(id = id, leak = true, items = items, fix = fix, appView = true, modules = modules)
        }
}
