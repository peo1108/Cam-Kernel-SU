package cam.su.kernel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import cam.su.kernel.jailbreak.CamBootReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * Reads every call to `ContextCompat.registerReceiver` in what the release APK ships: Cam's own
 * classes and the release runtime libraries (build.gradle.kts passes them as
 * `cam.releaseClassJars`; test and debug-only libraries are left out). The flags are the last
 * argument, so the instruction just before the call shows them when they are a constant.
 * - A constant with RECEIVER_NOT_EXPORTED fails: put the permission back (drop the two
 *   `tools:node="remove"` entries in AndroidManifest.xml).
 * - Flags that are not a constant (passed in, computed, chosen by a branch), every call to
 *   core-ktx's `receiveBroadcasts` (flags is not its last argument), every call to
 *   `ContextCompat.obtainAndCheckReceiverPermission` (the check that throws, open to every AndroidX
 *   library), and every other use of these methods (a method reference such as
 *   `ContextCompat::registerReceiver`) fail until someone reads the call and lists it in [REVIEWED].
 *
 * Cam's own classes come from the debug build (the unit test build): [releaseHasNoCodeOfItsOwn]
 * keeps release-only code from slipping past.
 */
class ReceiverPermissionTest {

    companion object {
        private const val CONTEXT_COMPAT = "androidx/core/content/ContextCompat"
        private const val CONTEXT_KT = "androidx/core/content/ContextKt"
        private const val REGISTER = "$CONTEXT_COMPAT.registerReceiver"
        private const val NOT_EXPORTED = ContextCompat.RECEIVER_NOT_EXPORTED

        /**
         * Methods that reach ContextCompat.registerReceiver with the flags their caller gives, and
         * the permission check itself (another AndroidX library may call it without registerReceiver).
         */
        private val CALLS = setOf(
            REGISTER,
            // core-ktx suspend helpers, their flags argument goes straight through
            "$CONTEXT_KT.receiveBroadcasts",
            "$CONTEXT_KT.receiveBroadcastsAsync",
            // RestrictTo(LIBRARY_GROUP_PREFIX): throws without the removed permission
            "$CONTEXT_COMPAT.obtainAndCheckReceiverPermission",
        )

        /** Where [CALLS] are defined: their callers are what counts. */
        private val WRAPPERS = setOf(CONTEXT_COMPAT, CONTEXT_KT)

        /**
         * Calls whose flags are not a constant, read and found never to carry RECEIVER_NOT_EXPORTED,
         * each as [Site.where] prints it: one call in one method, so another call in the same method
         * (or an overload) is read on its own.
         */
        private val REVIEWED = setOf<String>()

        /** Names [CALLS] by: a class file without one of them is not parsed at all. */
        private val NEEDLES = listOf("registerReceiver", "receiveBroadcasts", "obtainAndCheckReceiverPermission")
    }

    /**
     * A use of one of [CALLS]: where it is (`class.method(descriptor)#n`, the method's n-th use;
     * `class.<reference>#n` outside method code), and its flags when they are a constant.
     */
    private data class Site(val where: String, val call: String, val flags: Int?)

    @Test
    fun noReceiverNeedsTheRemovedPermission() {
        val jars = System.getProperty("cam.releaseClassJars")
        assertTrue("cam.releaseClassJars missing: run through Gradle's testDebugUnitTest", !jars.isNullOrEmpty())
        assumeTrue("only testDebugUnitTest scans the release classpath (build.gradle.kts)", jars != "skip")
        // Cam's own classes (Kotlin and Java, AIDL included): the debug build's runtime classes jar
        val own = listOf(CamApplication::class.java, CamBootReceiver::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }
        val roots = (jars!!.split(File.pathSeparator).map(::File) + own).distinct()

        var sawContextCompat = false
        val calls = ArrayList<Site>()
        forEachClass(roots) { path, content ->
            if (path == "$CONTEXT_COMPAT.class") sawContextCompat = true
            // a byte-for-char view, only to skip the class files that cannot use these methods
            val text = String(content, Charsets.ISO_8859_1)
            if (NEEDLES.any { text.contains(it) }) calls += sites(content)
        }
        // the scan is only worth something when it sees the libraries
        assertTrue("$CONTEXT_COMPAT not among the release class jars", sawContextCompat)

        val outside = calls.filterNot { site -> WRAPPERS.any { site.where.startsWith("$it.") || site.where.startsWith("$it\$") } }
        val notExported = outside.filter { it.flags != null && it.flags and NOT_EXPORTED != 0 }
        assertTrue("RECEIVER_NOT_EXPORTED without the permission, put it back (see the class comment): $notExported", notExported.isEmpty())
        val unread = outside.filter { it.flags == null && it.where !in REVIEWED }
        assertTrue("receiver calls with flags not known here, read them (see the class comment): $unread", unread.isEmpty())
    }

    @Test
    fun releaseHasNoCodeOfItsOwn() {
        // the scan reads Cam's classes from the debug build; code only in another source set
        // (src/release, a flavor) would ship without being read
        val code = File("src").listFiles()!!
            .filter { it.name !in setOf("main", "debug", "test", "androidTest") }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
        assertEquals(emptyList<File>(), code)
    }

    @Test
    fun readsTheFlagsAtEachCall() {
        val found = sites(classBytes(ReceiverCalls::class.java))
        val flags = found.associate { it.where.substringAfterLast('.').substringBefore('(') to it.flags }
        assertEquals(ContextCompat.RECEIVER_EXPORTED, flags["exported"])
        assertEquals(NOT_EXPORTED, flags["notExported"])
        assertEquals(null, flags["chosen"])
        assertEquals(null, flags["passed"])
        assertEquals(setOf("exported", "notExported", "chosen", "passed", "twice"), flags.keys)
        // each call is its own entry, so approving one does not approve the next
        val twice = found.filter { it.where.contains(".twice(") }.map { it.where.substringAfterLast(')') }
        assertEquals(listOf("Landroid/content/Intent;#0", "Landroid/content/Intent;#1"), twice)
    }

    @Test
    fun seesMethodReferences() {
        // javac turns ContextCompat::registerReceiver into an invokedynamic with a method handle,
        // no invokestatic: the call cannot be read, so it must be listed
        val found = sites(classBytes(ReceiverMethodRef::class.java))
        assertEquals(listOf(Site("cam/su/kernel/ReceiverMethodRef.<reference>#0", REGISTER, null)), found)
    }

    private fun classBytes(type: Class<*>) =
        javaClass.classLoader!!.getResourceAsStream("${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }

    /** Calls this test must read right; never shipped (test sources are not scanned). */
    @Suppress("unused")
    private object ReceiverCalls {
        fun exported(c: Context) = ContextCompat.registerReceiver(c, null, IntentFilter(), ContextCompat.RECEIVER_EXPORTED)
        fun notExported(c: Context) = ContextCompat.registerReceiver(c, null, IntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        fun chosen(c: Context, open: Boolean) = ContextCompat.registerReceiver(
            c, null, IntentFilter(), if (open) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        fun passed(c: Context, flags: Int) = ContextCompat.registerReceiver(c, null, IntentFilter(), flags)
        fun twice(c: Context, flags: Int): Intent? {
            ContextCompat.registerReceiver(c, null, IntentFilter(), flags)
            return ContextCompat.registerReceiver(c, null, IntentFilter(), flags)
        }
    }

    private fun forEachClass(roots: List<File>, action: (path: String, bytes: ByteArray) -> Unit) {
        fun visit(path: String, bytes: () -> ByteArray) {
            if (!path.endsWith(".class") || path.endsWith("module-info.class")) return
            action(path.replace(Regex("^META-INF/versions/\\d+/"), ""), bytes())
        }
        for (root in roots) when {
            root.isDirectory -> root.walk().filter { it.isFile }.forEach { f ->
                visit(f.relativeTo(root).invariantSeparatorsPath) { f.readBytes() }
            }
            root.isFile && root.extension == "jar" -> JarFile(root).use { jar ->
                for (e in jar.entries()) visit(e.name) { jar.getInputStream(e).use { it.readBytes() } }
            }
            // a missing or unknown root would leave its classes unread without failing
            else -> error("not a class directory or jar: $root")
        }
    }

    /** Every use of one of [CALLS] in a class file, read from its constant pool and method code. */
    private fun sites(bytes: ByteArray): List<Site> {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != 0xCAFEBABE.toInt()) return emptyList()
        input.skipBytes(4) // minor, major version
        val count = input.readUnsignedShort()
        val tags = IntArray(count)
        val utf8 = arrayOfNulls<String>(count)
        val ints = arrayOfNulls<Int>(count)
        val first = IntArray(count) // Class: name; Methodref: class; NameAndType: name; MethodHandle: Methodref
        val second = IntArray(count) // Methodref: name and type
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            tags[i] = tag
            when (tag) {
                1 -> utf8[i] = input.readUTF()
                3 -> ints[i] = input.readInt()
                4 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); i++ } // takes two slots
                7, 8, 16, 19, 20 -> first[i] = input.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { first[i] = input.readUnsignedShort(); second[i] = input.readUnsignedShort() }
                15 -> { input.skipBytes(1); first[i] = input.readUnsignedShort() } // kind, then the Methodref
                else -> error("constant pool tag $tag")
            }
            i++
        }
        fun call(index: Int): String? {
            if (tags[index] != 10 && tags[index] != 11) return null
            return "${utf8[first[first[index]]]}.${utf8[first[second[index]]]?.removeSuffix("\$default")}"
        }

        input.skipBytes(2) // access flags
        val self = utf8[first[input.readUnsignedShort()]]
        input.skipBytes(2) // super class
        input.skipBytes(2 * input.readUnsignedShort()) // interfaces
        fun skipAttributes() = repeat(input.readUnsignedShort()) { input.skipBytes(2); input.skipBytes(input.readInt()) }
        repeat(input.readUnsignedShort()) { input.skipBytes(6); skipAttributes() } // fields

        val found = ArrayList<Site>()
        val called = HashSet<Int>() // Methodrefs some invokestatic below uses
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2)
            val method = utf8[input.readUnsignedShort()] + utf8[input.readUnsignedShort()]
            var n = 0
            repeat(input.readUnsignedShort()) {
                val name = utf8[input.readUnsignedShort()]
                val length = input.readInt()
                if (name != "Code") {
                    input.skipBytes(length)
                    return@repeat
                }
                input.skipBytes(4) // max stack, max locals
                val code = ByteArray(input.readInt()).also(input::readFully)
                input.skipBytes(length - 8 - code.size) // exception table, attributes
                for ((_, index, flags) in invokeStatics(code, ints)) {
                    val target = call(index)?.takeIf { it in CALLS } ?: continue
                    called += index
                    found += Site("$self.$method#${n++}", target, flags.takeIf { target == REGISTER })
                }
            }
        }
        // Any other use: a method handle (method reference, invokedynamic), or a Methodref no
        // invokestatic here explains. Their flags cannot be read.
        val handled = (1 until count).filter { tags[it] == 15 }.map { first[it] }
        val unexplained = (1 until count).filter { tags[it] == 10 || tags[it] == 11 }.filter { it !in called && it !in handled }
        var n = 0
        for (index in handled + unexplained) {
            val target = call(index)?.takeIf { it in CALLS } ?: continue
            found += Site("$self.<reference>#${n++}", target, null)
        }
        return found
    }

    /**
     * Each invokestatic in [code]: its offset, its constant pool index, and the int constant the
     * instruction just before it pushes. That constant is dropped when the call is a jump target,
     * since another path may push something else.
     */
    private fun invokeStatics(code: ByteArray, ints: Array<Int?>): List<Triple<Int, Int, Int?>> {
        fun u1(at: Int) = code[at].toInt() and 0xff
        fun s2(at: Int) = (code[at].toInt() shl 8) or u1(at + 1)
        fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
        fun s4(at: Int) = (u2(at) shl 16) or u2(at + 2)

        val targets = HashSet<Int>()
        val invokes = ArrayList<Triple<Int, Int, Int?>>()
        var pc = 0
        var pushed: Int? = null
        while (pc < code.size) {
            val op = u1(pc)
            var constant: Int? = null
            val length = when (op) {
                in 0x02..0x08 -> { constant = op - 0x03; 1 } // iconst_m1 .. iconst_5
                0x10 -> { constant = code[pc + 1].toInt(); 2 } // bipush
                0x11 -> { constant = s2(pc + 1); 3 } // sipush
                0x12 -> { constant = ints[u1(pc + 1)]; 2 } // ldc
                0x13 -> { constant = ints[u2(pc + 1)]; 3 } // ldc_w
                0x14, 0x84 -> 3 // ldc2_w, iinc
                in 0x15..0x19, in 0x36..0x3a, 0xa9, 0xbc -> 2 // loads, stores, ret, newarray
                in 0x99..0xa8, 0xc6, 0xc7 -> { targets += pc + s2(pc + 1); 3 } // if*, goto, jsr
                0xc8, 0xc9 -> { targets += pc + s4(pc + 1); 5 } // goto_w, jsr_w
                0xaa -> { // tableswitch
                    val base = (pc + 4) and 3.inv()
                    val cases = s4(base + 8) - s4(base + 4) + 1
                    targets += pc + s4(base)
                    repeat(cases) { targets += pc + s4(base + 12 + 4 * it) }
                    base - pc + 12 + 4 * cases
                }
                0xab -> { // lookupswitch
                    val base = (pc + 4) and 3.inv()
                    val pairs = s4(base + 4)
                    targets += pc + s4(base)
                    repeat(pairs) { targets += pc + s4(base + 12 + 8 * it) }
                    base - pc + 8 + 8 * pairs
                }
                0xb8 -> { invokes += Triple(pc, u2(pc + 1), pushed); 3 } // invokestatic
                in 0xb2..0xb7, 0xbb, 0xbd, 0xc0, 0xc1 -> 3 // fields, invokes, new, anewarray, casts
                0xb9, 0xba -> 5 // invokeinterface, invokedynamic
                0xc4 -> if (u1(pc + 1) == 0x84) 6 else 4 // wide
                0xc5 -> 4 // multianewarray
                else -> 1
            }
            pushed = constant
            pc += length
        }
        return invokes.map { (at, index, flags) -> Triple(at, index, flags.takeIf { at !in targets }) }
    }
}
