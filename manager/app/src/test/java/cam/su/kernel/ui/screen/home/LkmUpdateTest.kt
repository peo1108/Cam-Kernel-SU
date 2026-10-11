package cam.su.kernel.ui.screen.home

import cam.su.kernel.KernelVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LkmUpdateTest {

    private fun state(running: String, bundled: String, camVersion: Int = 32800) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 0),
        camVersion = camVersion,
        managerUAPIVersion = 6,
        kernelUAPIVersion = 6,
        lkmMode = true,
        isLkmBundled = true,
        isManager = true,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = true,
        isSafeMode = false,
        isLateLoadMode = false,
        checkUpdateEnabled = true,
        currentManagerVersionCode = 32810,
        lkmSourceId = running,
        bundledLkmSourceId = bundled,
        systemInfo = SystemInfo("", "", "", "", "Enforcing", 2),
    )

    @Test
    fun sameKernelSourcesNeedNoReinstall() {
        // the version differs after every update; the kernel sources did not change
        assertFalse(state(running = "70d51c62", bundled = "70d51c62").showLkmUpdate)
    }

    @Test
    fun changedKernelSourcesOfferReinstall() {
        assertTrue(state(running = "70d51c62", bundled = "0123abcd").showLkmUpdate)
    }

    @Test
    fun unknownSourcesFallBackToTheVersion() {
        // a module from before KSU_IOCTL_GET_SOURCE_ID, or one built from uncommitted sources
        assertTrue(state(running = "", bundled = "70d51c62").showLkmUpdate)
        assertFalse(state(running = "", bundled = "70d51c62", camVersion = 32810).showLkmUpdate)
        // a Manager built outside git
        assertTrue(state(running = "70d51c62", bundled = "").showLkmUpdate)
    }
}
