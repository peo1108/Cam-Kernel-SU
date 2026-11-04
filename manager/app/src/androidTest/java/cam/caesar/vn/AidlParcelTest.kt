package cam.su.kernel

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import cam.su.kernel.files.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the AIDL interfaces carry, written and read back through a real Parcel the way binder does
 * (Parcelize code only runs on Android: JVM tests cannot check it). Needs no root.
 */
@RunWith(AndroidJUnit4::class)
class AidlParcelTest {

    /** Marshals [bundle] to bytes and back, as a call to another process would. */
    private fun throughParcel(bundle: Bundle): Bundle {
        val out = Parcel.obtain()
        val bytes = try {
            out.writeBundle(bundle)
            out.marshall()
        } finally {
            out.recycle()
        }
        val input = Parcel.obtain()
        try {
            input.unmarshall(bytes, 0, bytes.size)
            input.setDataPosition(0)
            return input.readBundle(javaClass.classLoader)!!
        } finally {
            input.recycle()
        }
    }

    @Test
    fun fileEntryKeepsEveryField() {
        val entry = FileEntry(
            name = "tên có dấu.txt",
            path = "/data/local/tmp/tên có dấu.txt",
            type = FileEntry.TYPE_LINK,
            size = 5_000_000_000L,
            mtime = 1_760_000_000_123L,
            mode = 0x81ed or 0x800,
            uid = 2000,
            gid = 1015,
            owner = "shell",
            group = "sdcard_rw",
            context = null,
            linkTarget = "/system/etc/hosts",
            linkBroken = true,
            targetIsDir = true,
            targetSize = 42,
        )
        val back = throughParcel(Bundle().apply { putParcelable("e", entry) })
        @Suppress("DEPRECATION")
        assertEquals(entry, back.getParcelable<FileEntry>("e"))
    }

    @Test
    fun appProfileSurvivesTheRootServiceBundle() {
        val profile = CamNative.Profile(
            name = "com.example.app",
            currentUid = 10_123,
            allowSu = true,
            rootUseDefault = false,
            rootTemplate = "tpl",
            uid = 2000,
            gid = 2000,
            groups = listOf(1004, 1007, 3003),
            capabilities = listOf(0, 21),
            context = "u:r:cam:s0",
            namespace = 2,
            nonRootUseDefault = false,
            umountModules = false,
            rules = "allow cam * * *",
            flags = 0L,
        )
        // the same helpers CamRootService and the Manager use on each side of ICamRootService
        val back = CamRootClient.readProfile(throughParcel(CamRootClient.writeProfile(profile)))
        assertEquals(profile, back)
    }

    @Test
    fun missingProfileReadsAsNull() {
        assertNull(CamRootClient.readProfile(null))
        assertNull(CamRootClient.readProfile(throughParcel(CamRootClient.writeProfile(null))))
    }
}
