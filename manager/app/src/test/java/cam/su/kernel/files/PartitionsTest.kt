package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionsTest {
    private val list = listOf(
        Partition("abl_a", 1), Partition("boot_a", 100), Partition("boot_b", 100), Partition("init_boot_a", 8),
        Partition("init_boot_b", 8), Partition("super", 9000), Partition("userdata", 99999), Partition("vbmeta_system_a", 1),
    )

    @Test
    fun onlyTheBootChainCanBeFlashed() {
        assertTrue(Partitions.canFlash("boot_a"))
        assertTrue(Partitions.canFlash("init_boot_b"))
        assertTrue(Partitions.canFlash("vbmeta_system_a"))
        assertTrue(Partitions.canFlash("recovery"))
        for (name in listOf("super", "userdata", "abl_a", "modem_a", "persist", "boot_c", "booty_a")) assertFalse(name, Partitions.canFlash(name))
        assertEquals("boot", Partitions.baseName("boot_a"))
        assertEquals("super", Partitions.baseName("super"))
    }

    @Test
    fun activeSlotAndFlashableFirst() {
        val names = Partitions.ordered(list, "_a").map { it.name }
        assertEquals(listOf("boot_a", "init_boot_a", "vbmeta_system_a", "boot_b", "init_boot_b"), names.take(5))
        assertTrue(Partitions.inSlot("super", "_b"))
        assertFalse(Partitions.inSlot("boot_b", "_a"))
    }

    @Test
    fun guessesThePartitionFromTheImageName() {
        assertEquals("init_boot_a", Partitions.guess("init_boot.img", list, "_a")?.name)
        assertEquals("boot_b", Partitions.guess("boot-magisk.img", list, "_b")?.name)
        assertEquals("init_boot_a", Partitions.guess("cam_patched_init_boot.img", list, "_a")?.name)
        assertNull(Partitions.guess("photo.img", list, "_a"))
        // a backup of the other slot goes back to that slot, not the one in use
        assertEquals("init_boot_b", Partitions.guess("init_boot_b_20261013-223459.img", list, "_a")?.name)
        assertEquals("boot_a", Partitions.guess("boot_a.img", list, "_b")?.name)
        assertTrue(Partitions.backupPath("boot_a", 0).startsWith("/storage/emulated/0/CamBackup/boot_a_"))
    }

    /** A header with [magic] and kernel_size (little endian, offset 8) set to [kernel]. */
    private fun header(magic: String, kernel: Int = 0): ByteArray = ByteArray(Partitions.HEADER_SIZE).also { h ->
        magic.toByteArray().copyInto(h)
        for (i in 0 until 4) h[8 + i] = (kernel shr (8 * i)).toByte()
    }

    @Test
    fun imagesMustMatchThePartitionFormat() {
        assertNull(Partitions.checkImage("boot_a", header("ANDROID!", kernel = 0x1234567)))
        assertNull(Partitions.checkImage("recovery", header("ANDROID!", kernel = 1)))
        assertNull(Partitions.checkImage("init_boot_b", header("ANDROID!")))
        assertNull(Partitions.checkImage("vendor_boot_a", header("VNDRBOOT")))
        assertNull(Partitions.checkImage("vbmeta_system_a", header("AVB0")))
        val dtbo = ByteArray(Partitions.HEADER_SIZE).also { byteArrayOf(0xd7.toByte(), 0xb7.toByte(), 0xab.toByte(), 0x1e).copyInto(it) }
        assertNull(Partitions.checkImage("dtbo_a", dtbo))

        // a zip, a vbmeta in boot, a boot image in vendor_boot, a file shorter than the magic
        assertEquals(ImageProblem.WRONG_FORMAT, Partitions.checkImage("boot_a", header("PK\u0003\u0004")))
        assertEquals(ImageProblem.WRONG_FORMAT, Partitions.checkImage("boot_a", header("AVB0")))
        assertEquals(ImageProblem.WRONG_FORMAT, Partitions.checkImage("vendor_boot", header("ANDROID!", kernel = 1)))
        assertEquals(ImageProblem.WRONG_FORMAT, Partitions.checkImage("vbmeta", "AV".toByteArray()))
        assertEquals(ImageProblem.WRONG_FORMAT, Partitions.checkImage("boot", "ANDROID!".toByteArray()))
    }

    @Test
    fun bootAndInitBootAreToldApartByTheKernel() {
        assertEquals(ImageProblem.KERNEL_INTO_INIT_BOOT, Partitions.checkImage("init_boot_a", header("ANDROID!", kernel = 4096)))
        assertEquals(ImageProblem.NO_KERNEL, Partitions.checkImage("boot_a", header("ANDROID!")))
        assertEquals(ImageProblem.NO_KERNEL, Partitions.checkImage("recovery", header("ANDROID!")))
        assertTrue(Partitions.backupRoom(100) > 100)
    }
}
