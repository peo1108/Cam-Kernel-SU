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
        assertEquals(1, status.leaks)
    }

    @Test
    fun bootconfigIsNotALeak() {
        val json = """
            {"bootconfig":[{"name":"androidboot.verifiedbootstate","ok":false,"safe":"green","value":"orange"},
                           {"name":"androidboot.vbmeta.device_state","ok":false,"safe":"locked","value":"unlocked"}],
             "enabled":true,
             "props":[{"current":"green","name":"ro.boot.verifiedbootstate","ok":true,"safe":"green"}]}
        """.trimIndent()
        assertEquals(0, HideBootloaderStatus.parse(json).leaks)
    }

    @Test
    fun yellowBootconfigIsCustomAvbKeyEvenWhenPropIsHidden() {
        val json = """
            {"bootconfig":[{"name":"androidboot.verifiedbootstate","ok":false,"safe":"green","value":"yellow"}],
             "enabled":true,
             "props":[{"current":"green","name":"ro.boot.verifiedbootstate","ok":true,"safe":"green"}]}
        """.trimIndent()
        assertEquals(true, HideBootloaderStatus.parse(json).customAvbKey)
    }

    @Test
    fun yellowPropCountsWithoutBootconfig() {
        val json = """
            {"bootconfig":[],"enabled":false,
             "props":[{"current":"yellow","name":"ro.boot.verifiedbootstate","ok":false,"safe":"green"}]}
        """.trimIndent()
        assertEquals(true, HideBootloaderStatus.parse(json).customAvbKey)
    }

    @Test
    fun unlockedOrUnknownIsNotCustomAvbKey() {
        val orange = """
            {"bootconfig":[{"name":"androidboot.verifiedbootstate","ok":false,"safe":"green","value":"orange"}],
             "enabled":false,
             "props":[{"current":"yellow","name":"ro.boot.verifiedbootstate","ok":false,"safe":"green"}]}
        """.trimIndent()
        assertEquals(false, HideBootloaderStatus.parse(orange).customAvbKey)
        assertEquals(false, HideBootloaderStatus.Empty.customAvbKey)
    }

    @Test
    fun corruptStatusIsEmpty() {
        assertEquals(HideBootloaderStatus.Empty, HideBootloaderStatus.parse("nope"))
    }
}
