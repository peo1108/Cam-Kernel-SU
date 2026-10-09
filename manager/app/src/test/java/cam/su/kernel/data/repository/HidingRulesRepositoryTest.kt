package cam.su.kernel.data.repository

import cam.su.kernel.hiding.HidingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HidingRulesRepositoryTest {

    private val rules = File("src/main/assets/hiding-rules.json")
    private val signature = File("src/main/assets/hiding-rules.json.sig")

    @Test
    fun shippedRulesAreSigned() {
        // fails when the rules changed without scripts/sign_hiding_rules.py
        assertTrue(HidingRulesRepository.verify(rules.readBytes(), signature.readText()))
    }

    @Test
    fun changedRulesFailTheSignature() {
        val tampered = rules.readText().replace("\"KSU\", ", "").toByteArray()
        assertFalse(HidingRulesRepository.verify(tampered, signature.readText()))
        assertFalse(HidingRulesRepository.verify(rules.readBytes(), "not base64"))
    }

    @Test
    fun shippedRulesParseAndRoundTrip() {
        val parsed = HidingRules.parse(rules.readText())
        assertNotNull(parsed)
        parsed!!
        assertTrue("KSU" in parsed.mountSources)
        assertEquals("green", parsed.safeProps["ro.boot.verifiedbootstate"])
        assertEquals(parsed, HidingRules.parse(parsed.toJson()))
    }

    @Test
    fun duckAheadComparesCommits() {
        val parsed = HidingRules.parse(rules.readText())!!
        val status = HidingRulesStatus(parsed, checkedAt = null)
        assertFalse(status.duckAhead)
        assertFalse(status.copy(duckLatest = parsed.duckDetector).duckAhead)
        assertTrue(status.copy(duckLatest = "a1b2c3d4").duckAhead)
    }
}
