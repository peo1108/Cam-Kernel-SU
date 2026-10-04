package me.weishu.kernelsu.data.model

import org.junit.Assert.assertEquals
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
        val json = """{"autoDisabled":[],"enabled":false,"failCount":0,"mode":"all","suspects":[],"threshold":4}"""
        val status = BootGuardStatus.parse(json)
        assertEquals(false, status.enabled)
        assertEquals(4, status.threshold)
        assertEquals(true, status.disableAll)
    }

    @Test
    fun oldStatusKeepsDefaults() {
        val status = BootGuardStatus.parse("""{"autoDisabled":[],"failCount":0,"suspects":[],"threshold":3}""")
        assertEquals(true, status.enabled)
        assertEquals(false, status.disableAll)
    }

    @Test
    fun corruptStatusIsEmpty() {
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse("{"))
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse(""))
    }
}
