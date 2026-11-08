package cam.caesar.vn.attest

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the attest feature's state lives on device (camd's `defs` mirror these). */
object AttestPaths {
    const val DIR = "/data/adb/cam/attest"
    const val BOOT_JSON = "$DIR/boot.json"
    const val PUSH_JSON = "$DIR/push.json"
    const val FLAG = "/data/adb/cam/.attest_sim"
    const val LIB = "/data/adb/cam/lib/libteesim_keymint.so"
    const val INJECT = "/data/adb/cam/bin/inject"
    // The packaged artifact names in the app's nativeLibraryDir.
    const val LIB_ASSET = "libteesim_keymint.so"
    const val INJECT_ASSET = "libteesiminject.so"
}

/**
 * Write [dest] atomically: fill a sibling `.tmp` via [write], then move it over [dest] (an atomic
 * rename on the device's filesystem). On any failure the temp is removed and [dest] is left
 * untouched, so camd's daemon never reads a half-written config.
 */
fun atomicWrite(dest: File, write: (File) -> Unit): Boolean {
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    return try {
        write(tmp)
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        tmp.delete()
        false
    }
}
