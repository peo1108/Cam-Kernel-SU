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
    fun corruptStatusIsEmpty() {
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse("{"))
        assertEquals(BootGuardStatus.Empty, BootGuardStatus.parse(""))
    }
}
