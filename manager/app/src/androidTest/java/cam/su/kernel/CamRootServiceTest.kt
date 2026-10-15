package cam.su.kernel

import android.content.pm.PackageManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ICamRootService against the real root process and kernel. Read-only: nothing here changes a
 * profile or a switch. Skipped (not failed) on a device where the Manager has no root.
 */
@RunWith(AndroidJUnit4::class)
class CamRootServiceTest {
    private lateinit var service: ICamRootService
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun bind() {
        assumeTrue("the Manager has no root on this device", runBlocking { CamRootClient.connect() })
        service = CamRootClient.service!!
    }

    @Test
    fun kernelSpeaksTheManagersUapi() {
        assertTrue("kernel version ${service.version}", service.version > 0)
        // a mismatch is what the Home screen reports as "kernel too old/new"
        assertEquals(CamNative.managerUAPIVersion, service.kernelUapiVersion)
        assertTrue("source id is empty", service.sourceId.isNotBlank())
    }

    @Test
    fun packagesCrossTheBinderInSlices() {
        // GET_PERMISSIONS makes the list far larger than one binder transaction (1 MB): only
        // ParcelableListSlice gets it across
        val fromRoot = service.getPackages(PackageManager.GET_PERMISSIONS).list
        val names = fromRoot.mapTo(HashSet()) { it.packageName }
        assertTrue("Manager missing from ${names.size} packages", context.packageName in names)
        @Suppress("DEPRECATION")
        val visibleHere = context.packageManager.getInstalledPackages(0).map { it.packageName }
        val missing = visibleHere.filter { it !in names }
        assertTrue("root service lost $missing", missing.isEmpty())
    }

    @Test
    fun userIdsIncludeThisUser() {
        val me = Process.myUid() / 100_000 // UserHandle.PER_USER_RANGE
        assertTrue(service.userIds.contentToString(), me in service.userIds)
    }

    @Test
    fun managerProfileAllowsRoot() {
        val profile = CamRootClient.readProfile(service.getAppProfile(context.packageName, Process.myUid()))
        assertNotNull(profile)
        assertEquals(context.packageName, profile!!.name)
        assertTrue("the Manager is pinned to root (pkg_tracker), its profile says otherwise", profile.allowSu)
    }

    @Test
    fun defaultNonRootProfileReads() {
        // "$" / nobody: the template every app without its own profile gets
        val profile = CamRootClient.readProfile(service.getAppProfile("$", 9999))
        assertNotNull(profile)
        assertTrue(!profile!!.allowSu)
    }

    @Test
    fun switchReadsAreStable() {
        // two reads in a row agree: the root side reads the kernel, not a stale cache
        assertEquals(service.isSuEnabled, service.isSuEnabled)
        assertEquals(service.isKernelUmountEnabled, service.isKernelUmountEnabled)
        assertTrue(service.superuserCount >= 1) // at least the Manager
    }
}
