package cam.su.kernel.hiding

import android.util.Log

/** Raw syscalls from libhidingprobe.so (cpp/hiding_probe.cc): what the kernel answers, past libc. */
object NativeProbe {

    val available: Boolean = runCatching { System.loadLibrary("hidingprobe") }
        .onFailure { Log.w("HidingProbe", "native probe unavailable", it) }
        .isSuccess

    /** The file's text read with openat/read syscalls; null when it cannot be opened or the library is missing. */
    fun read(path: String): String? = if (available) nativeRead(path) else null

    /** 0 when faccessat finds the path, else its errno; -1 without the library. */
    fun access(path: String): Int = if (available) nativeAccess(path) else -1

    /** attr/current write times, A and B interleaved ([AttrTiming]); null without the library. */
    fun attrTiming(): LongArray? = if (available) runCatching { attrTiming0() }.getOrNull() else null

    @JvmStatic
    private external fun attrTiming0(): LongArray?

    @JvmStatic
    private external fun read0(path: String): String?

    private fun nativeRead(path: String): String? = runCatching { read0(path) }.getOrNull()

    @JvmStatic
    private external fun access0(path: String): Int

    private fun nativeAccess(path: String): Int = runCatching { access0(path) }.getOrDefault(-1)
}
