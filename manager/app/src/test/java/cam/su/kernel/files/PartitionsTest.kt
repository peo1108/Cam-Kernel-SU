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
}
