package cam.caesar.vn.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkManifestTest {
    /** An attribute with its raw string value, or none and only a typed one ([typed] string, or an int). */
    private class Attr(val ns: String?, val name: String, val raw: String?, val typed: String? = raw, val int: Int? = null)

    private class Out {
        private val bytes = ByteArrayOutputStream()
        val size get() = bytes.size()
        fun u8(v: Int) = apply { bytes.write(v) }
        fun u16(v: Int) = apply { u8(v and 0xFF).u8((v shr 8) and 0xFF) }
        fun u32(v: Int) = apply { u16(v and 0xFFFF).u16((v ushr 16) and 0xFFFF) }
        fun raw(b: ByteArray) = apply { bytes.write(b) }
        fun pad() = apply { while (size % 4 != 0) u8(0) }
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Compiled XML laid out as aapt2 writes it: string pool, resource map, namespace, then [element]. */
    private fun axml(element: String, attrs: List<Attr>, utf8: Boolean): ByteArray {
        val strings = (listOf("versionCode", "android", androidNs, element) +
            attrs.flatMap { listOfNotNull(it.name, it.raw, it.typed) }).distinct()
        fun idx(s: String?) = if (s == null) -1 else strings.indexOf(s)

        val data = Out()
        val offsets = strings.map { s ->
            val at = data.size
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                data.u8(s.length).u8(b.size).raw(b).u8(0)
            } else {
                data.u16(s.length)
                s.forEach { data.u16(it.code) }
                data.u16(0)
            }
            at
        }
        data.pad()
        val header = 28
        val pool = Out().u16(0x0001).u16(header).u32(header + 4 * strings.size + data.size)
            .u32(strings.size).u32(0).u32(if (utf8) 0x100 else 0).u32(header + 4 * strings.size).u32(0)
        offsets.forEach { pool.u32(it) }
        pool.raw(data.toByteArray())

        val resMap = Out().u16(0x0180).u16(8).u32(12).u32(0x0101021b)
        val ns = Out().u16(0x0100).u16(16).u32(24).u32(1).u32(-1).u32(idx("android")).u32(idx(androidNs))
        val start = Out().u16(0x0102).u16(16).u32(16 + 20 + 20 * attrs.size).u32(1).u32(-1)
            .u32(-1).u32(idx(element)).u16(20).u16(20).u16(attrs.size).u16(0).u16(0).u16(0)
        for (a in attrs) {
            // Res_value: size, res0, dataType (0x03 string, 0x10 int), data
            val (type, value) = if (a.int != null) 0x10 to a.int else 0x03 to idx(a.typed)
            start.u32(idx(a.ns)).u32(idx(a.name)).u32(idx(a.raw)).u16(8).u8(0).u8(type).u32(value)
        }
        val body = pool.toByteArray() + resMap.toByteArray() + ns.toByteArray() + start.toByteArray()
        return Out().u16(0x0003).u16(8).u32(8 + body.size).raw(body).toByteArray()
    }

    private fun manifest(pkg: Attr) = listOf(Attr(androidNs, "versionCode", null, int = 42), pkg)

    private val game = manifest(Attr(null, "package", "com.example.game"))

    @Test
    fun readsThePackageFromEitherStringPool() {
        assertEquals("com.example.game", ApkManifest.packageOf(axml("manifest", game, utf8 = false)))
        assertEquals("com.example.game", ApkManifest.packageOf(axml("manifest", game, utf8 = true)))
    }

    @Test
    fun readsAPackageGivenOnlyAsATypedString() {
        // shrinkers may drop the raw value and keep the typed one
        val typed = manifest(Attr(null, "package", null, typed = "com.typed.app"))
        assertEquals("com.typed.app", ApkManifest.packageOf(axml("manifest", typed, utf8 = true)))
        val number = manifest(Attr(null, "package", null, int = 7))
        assertNull(ApkManifest.packageOf(axml("manifest", number, utf8 = true)))
    }

    @Test
    fun refusesWhatIsNotAManifestWithAPackage() {
        assertNull(ApkManifest.packageOf(axml("application", game, utf8 = false)))
        assertNull(ApkManifest.packageOf(axml("manifest", manifest(Attr(null, "package", "../../data")), utf8 = true)))
        assertNull(ApkManifest.packageOf(axml("manifest", manifest(Attr(null, "package", "single")), utf8 = true)))
        // only the attribute without a namespace is the package
        assertNull(ApkManifest.packageOf(axml("manifest", manifest(Attr(androidNs, "package", "com.x.y")), utf8 = true)))
        assertNull(ApkManifest.packageOf(ByteArray(0)))
        assertNull(ApkManifest.packageOf("<manifest package=\"com.x.y\"/>".toByteArray()))
    }

    @Test
    fun brokenDataGivesNullAndNoException() {
        val whole = axml("manifest", game, utf8 = false)
        for (n in whole.indices) ApkManifest.packageOf(whole.copyOf(n))
        // the element's name claims to be gigabytes long
        val huge = whole.copyOf()
        val buf = ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN)
        val pool = 8
        val name = pool + buf.getInt(pool + 20) + buf.getInt(pool + 28 + 3 * 4)
        buf.putShort(name, 0xFFFF.toShort())
        buf.putShort(name + 2, 0xFFFF.toShort())
        assertNull(ApkManifest.packageOf(huge))
    }

    /** A zip of [entries]; [stored] ones are kept as is (sizes and CRC up front, as zipalign leaves them). */
    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Sets the "encrypted" flag of [name] in its local and central headers, as packers do; Android ignores it. */
    private fun fakeEncrypted(zip: ByteArray, name: String): ByteArray {
        val out = zip.copyOf()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val wanted = name.toByteArray()
        fun nameAt(at: Int, length: Int) = length == wanted.size && out.copyOfRange(at, at + length).contentEquals(wanted)
        for (i in 0..out.size - 46) {
            when (buf.getInt(i)) {
                0x04034b50 -> if (nameAt(i + 30, buf.getShort(i + 26).toInt())) out[i + 6] = (out[i + 6].toInt() or 1).toByte()
                0x02014b50 -> if (nameAt(i + 46, buf.getShort(i + 28).toInt())) out[i + 8] = (out[i + 8].toInt() or 1).toByte()
            }
        }
        return out
    }

    private fun file(data: ByteArray) = File.createTempFile("bundle", ".xapk").apply {
        deleteOnExit()
        writeBytes(data)
    }

    @Test
    fun readsAnApkStoredInABundleFromItsCentralDirectory() {
        val manifest = axml("manifest", game, utf8 = false)
        val packed = fakeEncrypted(zip("classes.dex" to ByteArray(5000) { it.toByte() }, "AndroidManifest.xml" to manifest), "AndroidManifest.xml")
        // read front to back, the flag stops it; Android and the central directory read it fine
        assertNull(runCatching { ApkManifest.packageName(packed.inputStream()) }.getOrNull())
        val bundle = file(zip("base.apk" to packed, "Android/obb/com.example.game/main.1.obb" to ByteArray(10), stored = setOf("base.apk")))
        assertEquals("com.example.game", ApkManifest.packageNameInBundle(bundle, "base.apk"))
        // a manifest stored as is inside the APK too
        val plain = zip("AndroidManifest.xml" to manifest, "classes.dex" to ByteArray(10), stored = setOf("AndroidManifest.xml"))
        assertEquals("com.example.game", ApkManifest.packageNameInBundle(file(zip("base.apk" to plain, stored = setOf("base.apk"))), "base.apk"))
        assertNull(ApkManifest.packageNameInBundle(bundle, "missing.apk"))
    }

    @Test
    fun readsACompressedApkFrontToBackAndStopsWhenCancelled() {
        val apk = zip("classes.dex" to ByteArray(5000), "AndroidManifest.xml" to axml("manifest", game, utf8 = true))
        val bundle = file(zip("base.apk" to apk))
        assertEquals("com.example.game", ApkManifest.packageNameInBundle(bundle, "base.apk"))
        try {
            ApkManifest.packageNameInBundle(bundle, "base.apk") { true }
            fail("expected a cancel")
        } catch (_: OpCancelledException) {
        }
    }

    @Test
    fun findsTheManifestInsideAnApk() {
        val apk = ByteArrayOutputStream()
        ZipOutputStream(apk).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(1000) { it.toByte() })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(axml("manifest", game, utf8 = false))
            zip.closeEntry()
        }
        assertEquals("com.example.game", ApkManifest.packageName(apk.toByteArray().inputStream()))
        val none = ByteArrayOutputStream()
        ZipOutputStream(none).use {
            it.putNextEntry(ZipEntry("classes.dex"))
            it.closeEntry()
        }
        assertNull(ApkManifest.packageName(none.toByteArray().inputStream()))
    }
}
