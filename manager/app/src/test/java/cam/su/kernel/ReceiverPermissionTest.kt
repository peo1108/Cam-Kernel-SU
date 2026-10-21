package cam.su.kernel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile

/**
 * The Manifest drops `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (upstream #3927,
 * docs/CAM_CHANGES.md section 2). AndroidX adds it for one reason: on Android 12/12L (API 31/32,
 * inside minSdk) `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` checks the app holds
 * it and throws when it does not, so such a call would crash the Manager there.
 *
 * Lists every class on the app's classpath (Cam's code and its libraries) that calls
 * `ContextCompat.registerReceiver`, directly or through core-ktx's `receiveBroadcasts`. A class
 * not in [REVIEWED] fails this test until someone reads
 * it: when it can pass `RECEIVER_NOT_EXPORTED`, put the permission back (drop the two
 * `tools:node="remove"` entries in AndroidManifest.xml); when it cannot, add it here.
 */
class ReceiverPermissionTest {

    companion object {
        private const val CONTEXT_COMPAT = "androidx/core/content/ContextCompat"
        private const val CONTEXT_KT = "androidx/core/content/ContextKt"

        /** Methods that reach ContextCompat.registerReceiver with the flags their caller gives. */
        private val CALLS = setOf(
            "$CONTEXT_COMPAT.registerReceiver",
            // core-ktx suspend helpers, their flags argument goes straight through
            "$CONTEXT_KT.receiveBroadcasts",
            "$CONTEXT_KT.receiveBroadcastsAsync",
        )

        /** Where [CALLS] are defined: their callers are what counts. */
        private val WRAPPERS = setOf(CONTEXT_COMPAT, CONTEXT_KT)

        /** Callers read and found to pass RECEIVER_EXPORTED only, which needs no permission. */
        private val REVIEWED = setOf(
            // initializeMediaQueryListeners (Compose BOM 2026.09.00): RECEIVER_EXPORTED (iconst_2)
            "androidx/compose/ui/platform/AndroidComposeView",
        )
    }

    @Test
    fun noUnreviewedContextCompatReceiver() {
        val classes = classpathClasses()
        // the scan is only worth something when it sees the libraries
        assertTrue("$CONTEXT_COMPAT not on the test classpath", CONTEXT_COMPAT in classes)
        // a wrapper's own nested classes (coroutine continuations) are the wrapper
        val callers = classes.filterValues { it }.keys.filterNot { c -> WRAPPERS.any { c == it || c.startsWith("$it\$") } }
        val unreviewed = callers - REVIEWED
        assertTrue("new ContextCompat.registerReceiver callers, read them (see the class comment): $unreviewed", unreviewed.isEmpty())
    }

    /** Every class name on the classpath, mapped to whether it calls one of [CALLS]. */
    private fun classpathClasses(): Map<String, Boolean> {
        val found = HashMap<String, Boolean>()
        fun add(path: String, bytes: () -> ByteArray) {
            if (!path.endsWith(".class") || path.endsWith("module-info.class")) return
            val name = path.removeSuffix(".class").replace(Regex("^META-INF/versions/\\d+/"), "")
            found[name] = found[name] == true || callsReceiverApi(bytes())
        }
        for (entry in System.getProperty("java.class.path").split(File.pathSeparator)) {
            val file = File(entry)
            when {
                file.isDirectory -> file.walk().filter { it.isFile }.forEach { f ->
                    add(f.relativeTo(file).invariantSeparatorsPath) { f.readBytes() }
                }
                file.isFile && file.extension == "jar" -> JarFile(file).use { jar ->
                    for (e in jar.entries()) add(e.name) { jar.getInputStream(e).use { it.readBytes() } }
                }
            }
        }
        return found
    }

    /** True when the class file's constant pool has a method ref to one of [CALLS]. */
    private fun callsReceiverApi(bytes: ByteArray): Boolean {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != 0xCAFEBABE.toInt()) return false
        input.skipBytes(4) // minor, major version
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNames = IntArray(count)
        val memberNames = IntArray(count)
        val methods = ArrayList<Pair<Int, Int>>()
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                7 -> classNames[i] = input.readUnsignedShort()
                10 -> methods += input.readUnsignedShort() to input.readUnsignedShort()
                12 -> { memberNames[i] = input.readUnsignedShort(); input.skipBytes(2) }
                3, 4, 9, 11, 17, 18 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); i++ } // takes two slots
                8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                else -> error("constant pool tag $tag")
            }
            i++
        }
        return methods.any { (owner, member) ->
            "${utf8[classNames[owner]]}.${utf8[memberNames[member]]?.removeSuffix("\$default")}" in CALLS
        }
    }
}
