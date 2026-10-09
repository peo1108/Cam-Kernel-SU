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
 * follow the app side probes of Duck Detector, written small and without native code;
 * what they look for comes from [HidingRules].
 */
object AppViewProbe {

    private val PARTITIONS = listOf("/system", "/vendor", "/product", "/system_ext", "/odm")

    fun run(rules: HidingRules): HidingAudit {
        val findings = listOfNotNull(
            finding("appMounts", leak = true, items = mounts(read("/proc/self/mountinfo"), rules), fix = "kernelUmount"),
            finding("appMaps", leak = true, items = maps(read("/proc/self/maps"), rules), fix = null),
            finding("appSu", leak = true, items = rules.suPaths.filter(::visible), fix = null),
            finding("appProps", leak = true, items = props(rules, ::getSystemProperty), fix = "hideBootloader"),
            finding("appSelinux", leak = true, items = selinux(read("/sys/fs/selinux/enforce")), fix = null),
        )
        return HidingAudit(findings = findings, fixable = findings.count { it.fix != null })
    }

    /** Mount points that come from root tooling: its mount sources, its folders, or modules over a partition. */
    fun mounts(mountinfo: String, rules: HidingRules): List<String> = mountinfo.lineSequence().mapNotNull { line ->
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
        val suspicious = source in rules.mountSources ||
                listOf(root, point, options).any { "/data/adb" in it || "/debug_ramdisk" in it } ||
                (onPartition && type == "overlay") ||
                (onPartition && type == "tmpfs" && point != "/system" && "/apex" !in point)
        if (suspicious) "$point ($type, $source)" else null
    }.distinct().toList()

    fun maps(maps: String, rules: HidingRules): List<String> = maps.lineSequence().mapNotNull { line ->
        val path = line.substringAfter('/', "").let { if (it.isEmpty()) null else "/$it" }
            ?: line.split(' ').lastOrNull()?.takeIf { it.startsWith("[anon:") || it.startsWith("memfd:") }
            ?: return@mapNotNull null
        val lower = path.lowercase()
        path.takeIf { rules.mapMarkers.any { it in lower } }
    }.distinct().toList()

    fun props(rules: HidingRules, get: (String) -> String): List<String> = rules.safeProps.mapNotNull { (name, safe) ->
        val value = get(name)
        if (value.isEmpty() || value == safe) null else "$name=$value"
    } + get("ro.build.tags").let { if ("test-keys" in it) listOf("ro.build.tags=$it") else emptyList() }

    fun selinux(enforce: String): List<String> =
        if (enforce.trim() == "0") listOf("/sys/fs/selinux/enforce=0") else emptyList()

    /** A path exists when stat works, or is refused (EACCES) rather than missing. */
    private fun visible(path: String): Boolean = try {
        Os.stat(path)
        true
    } catch (e: ErrnoException) {
        e.errno == OsConstants.EACCES
    }

    private fun read(path: String): String = runCatching { File(path).readText() }.getOrDefault("")

    private fun finding(id: String, leak: Boolean, items: List<String>, fix: String?) =
        if (items.isEmpty()) null else HidingAudit.Finding(id = id, leak = leak, items = items, fix = fix, appView = true)
}
