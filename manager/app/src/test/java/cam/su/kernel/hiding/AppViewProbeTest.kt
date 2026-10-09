package cam.su.kernel.hiding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppViewProbeTest {

    /** the rules the APK ships */
    private val rules = HidingRules.parse(File("src/main/assets/hiding-rules.json").readText())!!

    @Test
    fun stockMountsAreClean() {
        val mountinfo = """
            25 1 253:0 / / ro,relatime shared:1 - ext4 /dev/block/dm-0 ro,seclabel
            30 25 0:22 / /dev rw,nosuid,relatime shared:2 - tmpfs tmpfs rw,seclabel,mode=755
            40 25 253:1 / /vendor ro,relatime shared:5 - ext4 /dev/block/dm-1 ro,seclabel
            50 25 0:30 / /apex rw,nosuid,nodev,noexec,relatime shared:7 - tmpfs tmpfs rw,seclabel
            60 25 254:5 / /data rw,nosuid,nodev,noatime shared:30 - f2fs /dev/block/dm-5 rw,seclabel
        """.trimIndent()
        assertEquals(emptyList<String>(), AppViewProbe.mounts(mountinfo, rules))
    }

    @Test
    fun moduleMountsAreFound() {
        val mountinfo = """
            25 1 253:0 / / ro,relatime shared:1 - ext4 /dev/block/dm-0 ro,seclabel
            70 25 0:40 / /system/bin rw,relatime shared:40 - overlay KSU ro,lowerdir=/data/adb/modules/a/system/bin:/system/bin
            71 25 0:41 / /product/overlay rw,relatime - tmpfs tmpfs rw
            72 60 254:5 /adb/modules /data/adb/modules rw,relatime - f2fs /dev/block/dm-5 rw
        """.trimIndent()
        assertEquals(
            listOf(
                "/system/bin (overlay, KSU)",
                "/product/overlay (tmpfs, tmpfs)",
                "/data/adb/modules (f2fs, /dev/block/dm-5)",
            ),
            AppViewProbe.mounts(mountinfo, rules),
        )
    }

    @Test
    fun hookLibrariesInMapsAreFound() {
        val maps = """
            7f0000-7f1000 r-xp 00000000 fd:05 123   /system/lib64/libc.so
            7f1000-7f2000 r-xp 00000000 fd:05 124   /data/adb/modules/zygisk_lsposed/lib/liblspd.so
            7f2000-7f3000 r--p 00000000 00:01 125   /memfd:zygisk-loader (deleted)
            7f3000-7f4000 rw-p 00000000 00:00 0     [anon:dalvik-main space]
            7f4000-7f5000 r-xp 00000000 fd:05 124   /data/adb/modules/zygisk_lsposed/lib/liblspd.so
        """.trimIndent()
        assertEquals(
            listOf("/data/adb/modules/zygisk_lsposed/lib/liblspd.so", "/memfd:zygisk-loader (deleted)"),
            AppViewProbe.maps(maps, rules),
        )
    }

    @Test
    fun unlockedPropsAreFound() {
        val props = mapOf(
            "ro.boot.verifiedbootstate" to "orange",
            "ro.boot.flash.locked" to "1",
            "ro.boot.vbmeta.device_state" to "unlocked",
            "ro.build.tags" to "release-keys",
        )
        // the JVM's org.json does not keep key order
        assertEquals(
            setOf("ro.boot.verifiedbootstate=orange", "ro.boot.vbmeta.device_state=unlocked"),
            AppViewProbe.props(rules) { props[it].orEmpty() }.toSet(),
        )
        assertEquals(listOf("ro.build.tags=test-keys"), AppViewProbe.props(rules) { if (it == "ro.build.tags") "test-keys" else "" })
    }

    @Test
    fun permissiveSelinuxIsFound() {
        assertEquals(listOf("/sys/fs/selinux/enforce=0"), AppViewProbe.selinux("0\n"))
        assertEquals(emptyList<String>(), AppViewProbe.selinux("1"))
        assertEquals(emptyList<String>(), AppViewProbe.selinux(""))
    }
}
