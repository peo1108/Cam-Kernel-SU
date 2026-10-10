package cam.su.kernel.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WirelessAdbTest {

    @Test
    fun onWhenAdbdRunsOnAPort() {
        val status = WirelessAdbStatus.parse(listOf("5555", "running", "1"))
        assertEquals(5555, status.port)
        assertTrue(status.on)
        assertTrue(status.adbEnabled)
    }

    @Test
    fun usbOnlyPortsAreOff() {
        // unset, `adb usb` (0), and the -1 some ROMs use
        for (port in listOf("", "0", "-1")) {
            val status = WirelessAdbStatus.parse(listOf(port, "running", "1"))
            assertNull(status.port)
            assertFalse(status.on)
        }
    }

    @Test
    fun aPortWithAdbdStoppedIsOff() {
        val status = WirelessAdbStatus.parse(listOf("5555", "stopped", "0"))
        assertFalse(status.on)
        assertFalse(status.adbEnabled)
        // settings prints "null" when adb_enabled was never set
        assertFalse(WirelessAdbStatus.parse(listOf("", "", "null")).adbEnabled)
        assertEquals(WirelessAdbStatus.Off, WirelessAdbStatus.parse(emptyList()))
    }

    @Test
    fun picksTheLanIpv4Address() {
        val addresses = listOf("fe80::1", "127.0.0.1", "169.254.3.4", "192.168.1.23", "10.0.0.2")
            .map { InetAddress.getByName(it) }
        assertEquals("192.168.1.23", WirelessAdb.ipv4(addresses))
        assertNull(WirelessAdb.ipv4(listOf(InetAddress.getByName("fe80::1"))))
    }
}
