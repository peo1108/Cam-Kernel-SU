package cam.su.kernel.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One partition under by-name: [name] as listed (with its slot suffix), [size] in bytes. */
data class Partition(val name: String, val size: Long)

/** Why an image must not go to a partition; see [Partitions.checkImage]. */
enum class ImageProblem {
    /** Not the format the partition holds (another partition's image, a zip, a compressed file). */
    WRONG_FORMAT,

    /** A boot image with a kernel going to init_boot (init_boot holds only the ramdisk). */
    KERNEL_INTO_INIT_BOOT,

    /** An image without a kernel (init_boot) going to boot or recovery: the phone would not boot. */
    NO_KERNEL,
}

/**
 * Partition backup and flashing for the Files tab. Any partition can be backed up (it is only
 * read); only the boot chain can be flashed, the images a root user swaps (a patched init_boot, a
 * stock boot back), never super, userdata, modem or the bootloader, where a wrong image bricks.
 */
object Partitions {
    val DIRS = listOf("/dev/block/by-name", "/dev/block/bootdevice/by-name")
    const val BACKUP_DIR = "/storage/emulated/0/CamBackup"

    private val FLASHABLE = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "recovery", "dtbo",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
    )

    /** "boot_a" → "boot"; a name without a slot stays as it is. */
    fun baseName(name: String): String = if (name.length > 2 && (name.endsWith("_a") || name.endsWith("_b"))) name.dropLast(2) else name

    fun canFlash(name: String) = baseName(name) in FLASHABLE

    /** Bytes of an image [checkImage] needs. */
    const val HEADER_SIZE = 64

    private val ANDROID_BOOT = "ANDROID!".toByteArray()
    private val VENDOR_BOOT = "VNDRBOOT".toByteArray()
    private val AVB = "AVB0".toByteArray()
    private val DT_TABLE = byteArrayOf(0xd7.toByte(), 0xb7.toByte(), 0xab.toByte(), 0x1e)

    /**
     * Whether the first [HEADER_SIZE] bytes of an image fit partition [name], null when they do.
     * Each partition of the boot chain starts with its own magic; boot and init_boot share one,
     * and tell apart by kernel_size (at offset 8 in every header version): only init_boot has none.
     */
    fun checkImage(name: String, header: ByteArray): ImageProblem? {
        val base = baseName(name)
        val magic = when (base) {
            "boot", "init_boot", "recovery" -> ANDROID_BOOT
            "vendor_boot", "vendor_kernel_boot" -> VENDOR_BOOT
            "dtbo" -> DT_TABLE
            else -> AVB
        }
        if (header.size < magic.size || !header.copyOf(magic.size).contentEquals(magic)) return ImageProblem.WRONG_FORMAT
        if (magic !== ANDROID_BOOT) return null
        if (header.size < 12) return ImageProblem.WRONG_FORMAT
        val kernelSize = (header[8].toInt() and 0xff) or ((header[9].toInt() and 0xff) shl 8) or
            ((header[10].toInt() and 0xff) shl 16) or ((header[11].toInt() and 0xff) shl 24)
        return when {
            base == "init_boot" && kernelSize != 0 -> ImageProblem.KERNEL_INTO_INIT_BOOT
            base != "init_boot" && kernelSize == 0 -> ImageProblem.NO_KERNEL
            else -> null
        }
    }

    /** Room the copy of the old partition needs before a flash: its size and a little to spare. */
    fun backupRoom(partitionSize: Long): Long = partitionSize + 16L * 1024 * 1024

    /** The partitions shown first: what can be flashed, the active slot before the other. */
    fun ordered(list: List<Partition>, slot: String): List<Partition> =
        list.sortedWith(compareBy<Partition>({ !canFlash(it.name) }, { !inSlot(it.name, slot) }, { it.name }))

    /** Whether [name] is the copy the phone boots from (names without a slot always are). */
    fun inSlot(name: String, slot: String): Boolean = baseName(name) == name || slot.isEmpty() || name.endsWith(slot)

    fun backupPath(name: String, time: Long): String =
        "$BACKUP_DIR/${name}_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(time))}.img"

    /** For a picked image, the partition its file name names ("init_boot.img" → init_boot), if any. */
    fun guess(fileName: String, list: List<Partition>, slot: String): Partition? {
        val lower = fileName.lowercase(Locale.ROOT)
        // a backup names its slot ("init_boot_b_2026...img"): that one, never the other slot
        list.filter { canFlash(it.name) && it.name != baseName(it.name) }
            .sortedByDescending { it.name.length }
            .firstOrNull { lower.startsWith("${it.name}_") || lower.startsWith("${it.name}.") }
            ?.let { return it }
        return list.filter { canFlash(it.name) && inSlot(it.name, slot) }
            .sortedByDescending { baseName(it.name).length }
            .firstOrNull { lower.startsWith(baseName(it.name)) || lower.contains("_${baseName(it.name)}") }
    }
}
