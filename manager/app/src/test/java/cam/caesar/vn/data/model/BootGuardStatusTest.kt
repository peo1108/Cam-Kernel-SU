package cam.su.kernel.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootGuardStatusTest {

    @Test
    fun parsesStatus() {
        val json = """
            {
              "autoDisabled": ["bg-test"],
              "failCount": 1,
              "lastTrigger": 12320886,
              "suspects": [],
              "threshold": 3
            }
        """.trimIndent()
        assertEquals(BootGuardStatus(failCount = 1, threshold = 3, autoDisabled = listOf("bg-test")), BootGuardStatus.parse(json))
    }

    @Test
    fun parsesConfig() {
        val json = """{"autoDisabled":[],"enabled":false,"failCount":0,"suspects":[],"threshold":4}"""
        val status = BootGuardStatus.parse(json)
        assertEquals(false, status.enabled)
        assertEquals(4, status.threshold)
    }

    @Test
    fun oldStatusKeepsDefaults() {
        // camd before 1.1.8-beta.3 still printed a "mode"
        val status = BootGuardStatus.parse("""{"autoDisabled":[],"failCount":0,"mode":"all","suspects":[],"threshold":3}""")
        assertEquals(true, status.enabled)
        assertTrue(status.culprits.isEmpty())
        assertTrue(status.initrcDisabled.isEmpty())
        assertFalse(status.hasNotice)
    }

    @Test
    fun parsesCulprits() {
        val json = """
            {
              "autoDisabled": ["a", "b"],
              "initrcDisabled": ["x.rc"],
              "culprits": [
                {"id": "b", "reasons": ["changed", "log", "future"], "evidence": "F DEBUG: #00 pc /system/lib64/libb.so"},
                {"id": "initrc.d/x.rc", "reasons": ["initrc_d"], "evidence": null},
                {"reasons": ["changed"]}
              ],
              "failCount": 0,
              "threshold": 3
            }
        """.trimIndent()
        val status = BootGuardStatus.parse(json)
        assertEquals(2, status.culprits.size)
        val b = status.culprits[0]
        assertEquals("b", b.id)
        assertEquals(listOf(BootGuardReason.Changed, BootGuardReason.Log), b.reasons)
        assertEquals("F DEBUG: #00 pc /system/lib64/libb.so", b.evidence)
        assertNull(b.initrcName)
        val initrc = status.culprits[1]
        assertEquals("x.rc", initrc.initrcName)
        assertNull(initrc.evidence)
        assertEquals(setOf("b"), status.culpritModules)
        assertEquals(listOf("x.rc"), status.initrcDisabled)
        assertTrue(status.hasNotice)
    }

    @Test
    fun onlyInitrcFilesIsStillANotice() {
        val status = BootGuardStatus.parse("""{"autoDisabled":[],"initrcDisabled":["x.rc"],"threshold":3}""")
        assertTrue(status.hasNotice)
    }

    @Test
    fun corruptStatusIsEmpty() {
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse("{"))
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse(""))
    }
}
