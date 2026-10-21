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

    @Test
    fun duckFeedParses() {
        // what deploy-site.yml writes from scripts/duck_nightly.sh
        val json = """{"sha":"41b35fec8838961c64437a90333c6bc49779a71c","tag":"nightly-2026-10-21-41b35fec","date":"2026-10-21T10:07:48Z"}"""
        assertEquals("41b35fec8838961c64437a90333c6bc49779a71c" to "2026-10-21T10:07:48Z", HidingRulesRepository.parseDuck(json))
        assertTrue(runCatching { HidingRulesRepository.parseDuck("""{"sha":"","date":"x"}""") }.isFailure)
        assertTrue(runCatching { HidingRulesRepository.parseDuck("<html>404</html>") }.isFailure)
    }
}
