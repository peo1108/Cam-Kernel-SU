package cam.su.kernel.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HideBootloaderStatusTest {

    @Test
    fun parsesStatus() {
        val json = """
            {"bootconfig":[{"name":"androidboot.verifiedbootstate","ok":false,"safe":"green","value":"orange"}],
             "enabled":true,
             "props":[{"current":"orange","name":"ro.boot.verifiedbootstate","ok":false,"safe":"green"},
                      {"current":"1","name":"ro.boot.flash.locked","ok":true,"safe":"1"}]}
        """.trimIndent()
        val status = HideBootloaderStatus.parse(json)
        assertEquals(true, status.enabled)
        assertEquals(PropCheck("ro.boot.verifiedbootstate", "orange", "green", false), status.props[0])
        assertEquals(PropCheck("androidboot.verifiedbootstate", "orange", "green", false), status.bootconfig[0])
        assertEquals(2, status.leaks)
    }

    @Test
    fun corruptStatusIsEmpty() {
        assertEquals(HideBootloaderStatus.Empty, HideBootloaderStatus.parse("nope"))
    }
}
