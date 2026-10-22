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
                listOf(root, point, options).any { "/data/adb" in it || "/debug_ramdisk" in it } ||
                (onPartition && type == "overlay") ||
                (onPartition && type == "tmpfs" && point != "/system")
        if (suspicious) SeenMount("$point ($type, $source)", "$root $options") else null
    }.distinctBy { it.item }.toList()

    fun maps(maps: String, rules: HidingRules): List<String> = maps.lineSequence().mapNotNull { line ->
        val path = line.substringAfter('/', "").let { if (it.isEmpty()) null else "/$it" }
            ?: line.split(' ').lastOrNull()?.takeIf { it.startsWith("[anon:") || it.startsWith("memfd:") }
            ?: return@mapNotNull null
        // what camd matches: no " (deleted)", ASCII lower case (lowercase() turns the Kelvin sign into k)
        val lower = asciiLowercase(path.removeSuffix(" (deleted)"))
        fun marked(p: String) = rules.mapMarkers.any { it in p }
        // cutting ids only takes text away, so most of the thousands of lines need no cut
        path.takeIf { marked(lower) && marked(withoutInstallIds(lower)) }
    }.distinct().toList()

    internal fun asciiLowercase(s: String) = String(CharArray(s.length) { s[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    // /data/app/~~<id>==/<pkg>-<id>==/base.apk -> /data/app/<pkg>/base.apk, also without ~~
    // (Android 8 to 10) and under /mnt/expand/<uuid>/app: the random ids can hold a marker
    // (WebView's -...EdXpOQh3UA== reads as EdXposed, -aRiRuQ3-... as Riru). Only the install
    // folders are cut, so an id shaped name elsewhere keeps its marker. camd's
    // without_install_ids (hiding_audit.rs) does the same; both tests read install-id-cases.json.
    internal fun withoutInstallIds(path: String): String {
        val parts = ArrayList<String>()
        // the part before is app, or the ~~<id> folder in it
        var inApp = false
        for (part in path.split('/')) {
            if (inApp && part.startsWith("~~") && isInstallId(part, 2)) continue
            val dash = part.length - INSTALL_ID_LENGTH - 1
            parts += if (inApp && dash >= 0 && part[dash] == '-' && isInstallId(part, dash + 1)) part.substring(0, dash) else part
            inApp = part == "app"
        }
        return parts.joinToString("/")
    }

    private const val INSTALL_ID_LENGTH = 24

    // 16 random bytes in URL-safe base64 from start to the end: 22 characters and ==. They
    // can hold a '-', so an id is found by its length, not at the last '-'.
    private fun isInstallId(s: String, start: Int): Boolean =
        s.length - start == INSTALL_ID_LENGTH && s.endsWith("==") && (start until s.length - 2).all {
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
        // the native side turns every non-ASCII byte into '?'; do the same here
        val seen = String(libc.toByteArray().map { if (it < 0) '?'.code.toByte() else it }.toByteArray())
            .lines().toSet()
        val hidden = raw.lines().filter { it.isNotBlank() && it !in seen }
        return if (hidden.isEmpty()) emptyList() else listOf("$MOUNTINFO: ${hidden.size} hidden from libc") +
                hidden.take(3).map { "  ${it.substringAfter(" - ", it)}" }
    }

    private fun hooked(rules: HidingRules): List<String> {
        val raw = NativeProbe.read(MOUNTINFO)
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

    private fun libcRead(path: String): String = runCatching { File(path).readText() }.getOrDefault("")

    private fun rawOrLibc(path: String): String = NativeProbe.read(path) ?: libcRead(path)

    private fun finding(id: String, items: List<String>, fix: String?, modules: List<String> = emptyList()) =
        if (items.isEmpty()) {
            null
        } else {
            HidingAudit.Finding(id = id, leak = true, items = items, fix = fix, appView = true, modules = modules)
        }
}
