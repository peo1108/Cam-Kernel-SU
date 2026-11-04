package cam.su.kernel.ui.util.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = tmp.newFile("module.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun readsIdFromModuleProp() {
        val file = zip("system/etc/x" to "", "module.prop" to "name=Test\r\n id = cf-a \nversion=v1\n")
        assertEquals("cf-a", readModuleIdFromZip(file))
    }

    @Test
    fun missingPropOrIdIsNull() {
        assertNull(readModuleIdFromZip(zip("module.prop" to "name=Test\n")))
        tmp.root.resolve("module.zip").delete()
        assertNull(readModuleIdFromZip(zip("other.txt" to "id=x\n")))
        assertNull(readModuleIdFromZip(tmp.root.resolve("missing.zip")))
    }
}
