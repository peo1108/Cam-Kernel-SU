package cam.su.kernel.donate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VietQrTest {

    @Test
    fun crcMatchesCcittFalseCheckValue() {
        assertEquals("29B1", VietQr.crc16("123456789"))
    }

    @Test
    fun staticPayloadLayout() {
        val p = VietQr.payload("970436", "0123456789")
        val expectedBody = "000201" + "010211" +
            "3854" + "0010A000000727" + "0124" + "0006970436" + "01100123456789" + "0208QRIBFTTA" +
            "5303704" + "5802VN" + "6304"
        assertEquals(expectedBody + VietQr.crc16(expectedBody), p)
    }

    @Test
    fun amountAndMessageMakeItDynamic() {
        val p = VietQr.payload("970436", "0123456789", amount = 50000, message = "ung ho Cam")
        assert(p.startsWith("000201010212"))
        assert(p.contains("540550000"))
        assert(p.contains("62140810ung ho Cam"))
        assertEquals(VietQr.crc16(p.dropLast(4)), p.takeLast(4))
    }

    @Test
    fun rejectsBadBin() {
        assertThrows(IllegalArgumentException::class.java) { VietQr.payload("97043", "1") }
    }
}
