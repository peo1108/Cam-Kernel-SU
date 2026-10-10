package cam.su.kernel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateInstallerTest {

    @Test
    fun sha256OfKnownBytes() {
        val file = File.createTempFile("ota", ".bin").apply {
            writeText("abc")
            deleteOnExit()
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
    }

    @Test
    fun installScriptShape() {
        val script = rootInstallScript(File("/data/x/ota/a.apk"), 1234, File("/data/x/ota/result"))
        // leaves the app's cgroup first, so Android killing the app does not kill the install
        assertTrue(script.indexOf("cgroup.procs") < script.indexOf("pm install"))
        assertTrue(script.contains("cat '/data/x/ota/a.apk' | pm install -r -S 1234 > '/data/x/ota/result' 2>&1"))
        val reopen = script.indexOf("am start -n cam.su.kernel/.ui.CamActivity")
        val check = script.indexOf("grep -q Success '/data/x/ota/result' &&")
        assertTrue(check in 0 until reopen)
    }
}
