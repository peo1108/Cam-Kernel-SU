package cam.su.kernel.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One partition under by-name: [name] as listed (with its slot suffix), [size] in bytes. */
data class Partition(val name: String, val size: Long)

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
