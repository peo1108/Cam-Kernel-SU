package cam.su.kernel.files

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry

/**
 * The package name in an APK's AndroidManifest.xml, read from the compiled (binary) XML without
 * PackageManager: the root process installs bundles, whose APKs are entries inside a zip.
 */
object ApkManifest {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val MAX_MANIFEST = 4 shl 20

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 1 shl 8
    private const val NO_ENTRY = -1
    private const val TYPE_STRING = 0x03

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    /**
     * The package of the APK stored as entry [name] of the bundle [bundle]. An APK stored as is
     * (the usual case) is read the way Android reads it, from its central directory: only the
     * manifest is touched, however big the APK, and a packer's odd local headers or "encrypted"
     * flags do not matter. A compressed one can only be read front to back. [cancelled] is checked
     * while reading; a cancel throws [OpCancelledException].
     */
    fun packageNameInBundle(bundle: File, name: String, cancelled: () -> Boolean = { false }): String? =
        ZipFile.builder().setFile(bundle).get().use { zip ->
            val entry = zip.getEntry(name) ?: return null
            if (entry.method == ZipEntry.STORED) {
                // the bundle's local headers were read on opening: the data offset is known
                FileChannel.open(bundle.toPath(), StandardOpenOption.READ).use { file ->
                    packageName(SliceChannel(file, entry.dataOffset, entry.size))
                }
            } else {
                zip.getInputStream(entry).use { packageName(it, cancelled) }
            }
        }

    /** The package of the APK [apk] (random access), from its central directory. */
    fun packageName(apk: SeekableByteChannel): String? =
        ZipFile.builder().setSeekableByteChannel(apk).setIgnoreLocalFileHeader(true).get().use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: return null
            if (entry.size > MAX_MANIFEST || entry.compressedSize > MAX_MANIFEST) return null
            // raw bytes: no check of the flags Android ignores too
            val raw = zip.getRawInputStream(entry) ?: return null
            when (entry.method) {
                ZipEntry.STORED -> raw.use { packageOf(readBounded(it, cancelled = { false }) ?: return null) }
                ZipEntry.DEFLATED -> {
                    val inflater = Inflater(true)
                    try {
                        // raw deflate wants one byte past the end of the data
                        InflaterInputStream(SequenceInputStream(raw, ByteArrayInputStream(ByteArray(1))), inflater).use {
                            packageOf(readBounded(it, cancelled = { false }) ?: return null)
                        }
                    } finally {
                        inflater.end()
                    }
                }
                else -> null
            }
        }

    /** The package of the APK read front to back from [apk], or null when it has no manifest that reads. */
    fun packageName(apk: InputStream, cancelled: () -> Boolean = { false }): String? =
        ZipArchiveInputStream(apk, "UTF-8", true, true).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != MANIFEST) {
                if (cancelled()) throw OpCancelledException()
                entry = zip.nextEntry
            }
            entry?.let { readBounded(zip, cancelled) }?.let(::packageOf)
        }

    private fun readBounded(input: InputStream, cancelled: () -> Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) throw OpCancelledException()
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > MAX_MANIFEST) return null
        }
    }

    /** Bytes [start, start + size) of [file], read-only: an APK stored inside a bundle. */
    private class SliceChannel(private val file: FileChannel, private val start: Long, private val size: Long) : SeekableByteChannel {
        private var pos = 0L

        override fun read(dst: ByteBuffer): Int {
            if (pos >= size) return -1
            val room = minOf(dst.remaining().toLong(), size - pos).toInt()
            val window = dst.duplicate().apply { limit(position() + room) }
            val n = file.read(window, start + pos)
            if (n > 0) {
                dst.position(dst.position() + n)
                pos += n
            }
            return n
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun position() = pos
        override fun position(newPosition: Long): SeekableByteChannel = apply { pos = newPosition }
        override fun size() = size
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        override fun isOpen() = file.isOpen
        // the bundle's channel belongs to the caller
        override fun close() = Unit
    }

    /** The `package` attribute of the root `<manifest>` element of compiled XML [data]. */
    fun packageOf(data: ByteArray): String? = try {
        parse(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))?.takeIf { PACKAGE.matches(it) }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parse(buf: ByteBuffer): String? {
        if (buf.limit() < 8 || u16(buf, 0) != RES_XML) return null
        var pool: StringPool? = null
        var pos = u16(buf, 2)
        while (pos + 8 <= buf.limit()) {
            val headerSize = u16(buf, pos + 2)
            val size = buf.getInt(pos + 4)
            if (size < 8 || size > buf.limit() - pos) return null
            when (u16(buf, pos)) {
                RES_STRING_POOL -> pool = StringPool(buf, pos)
                // the first element is <manifest>; nothing after it is needed
                RES_XML_START_ELEMENT -> return manifestPackage(buf, pos + headerSize, pool ?: return null)
            }
            pos += size
        }
        return null
    }

    // ResXMLTree_attrExt, then its ResXMLTree_attribute array: ns, name, rawValue, Res_value
    private fun manifestPackage(buf: ByteBuffer, ext: Int, pool: StringPool): String? {
        if (pool[buf.getInt(ext + 4)] != "manifest") return null
        val start = u16(buf, ext + 8)
        val attrSize = u16(buf, ext + 10)
        val count = u16(buf, ext + 12)
        for (i in 0 until count) {
            val a = ext + start + i * attrSize
            if (buf.getInt(a) != NO_ENTRY || pool[buf.getInt(a + 4)] != "package") continue
            val raw = buf.getInt(a + 8)
            return when {
                raw != NO_ENTRY -> pool[raw]
                buf.get(a + 15).toInt() == TYPE_STRING -> pool[buf.getInt(a + 16)]
                else -> null
            }
        }
        return null
    }

    private fun u16(buf: ByteBuffer, at: Int) = buf.getShort(at).toInt() and 0xFFFF

    private class StringPool(private val buf: ByteBuffer, pos: Int) {
        private val count = buf.getInt(pos + 8)
        private val utf8 = buf.getInt(pos + 16) and UTF8_FLAG != 0
        private val strings = pos + buf.getInt(pos + 20)
        private val offsets = pos + u16(buf, pos + 2)

        operator fun get(index: Int): String? {
            if (index < 0 || index >= count) return null
            var p = strings + buf.getInt(offsets + index * 4)
            if (utf8) {
                // the length in UTF-16 units first (unused), then in bytes; each 1 or 2 bytes
                p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = buf.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) {
                    len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
                    p += 2
                } else {
                    p += 1
                }
                if (len > buf.limit() - p) return null
                return String(ByteArray(len) { buf.get(p + it) }, Charsets.UTF_8)
            }
            var len = u16(buf, p)
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or u16(buf, p + 2)
                p += 4
            } else {
                p += 2
            }
            // a broken length must not ask for gigabytes before the read fails
            if (len * 2L > buf.limit() - p) return null
            return String(CharArray(len) { buf.getChar(p + it * 2) })
        }
    }
}
