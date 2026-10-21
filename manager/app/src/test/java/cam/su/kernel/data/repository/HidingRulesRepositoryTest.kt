package cam.su.kernel.data.repository

import cam.su.kernel.hiding.HidingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

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
    fun shippedRulesDateTheirPin() {
        // the date scripts/duck_nightly.sh prints next to the commit; duckAhead compares it
        val parsed = HidingRules.parse(rules.readText())!!
        assertTrue(parsed.duckDetector.matches(Regex("[0-9a-f]{40}")))
        Instant.parse(parsed.duckDetectorAt)
    }

    @Test
    fun duckAheadComparesCommitDates() {
        val parsed = HidingRules.parse(rules.readText())!!
        val pinAt = Instant.parse(parsed.duckDetectorAt)
        val status = HidingRulesStatus(parsed, checkedAt = null)
        assertFalse(status.duckAhead)
        assertFalse(status.copy(duckLatest = parsed.duckDetector, duckLatestAt = parsed.duckDetectorAt).duckAhead)
        assertTrue(status.copy(duckLatest = "a1b2c3d4", duckLatestAt = pinAt.plusSeconds(60).toString()).duckAhead)
        // a nightly older than the pin (the pin can be a commit no nightly has yet) is not ahead
        assertFalse(status.copy(duckLatest = "a1b2c3d4", duckLatestAt = pinAt.minusSeconds(60).toString()).duckAhead)
        // an answer without a readable date cannot be compared, so it is not ahead either
        assertFalse(status.copy(duckLatest = "a1b2c3d4", duckLatestAt = "").duckAhead)
        assertFalse(status.copy(duckLatest = "a1b2c3d4", duckLatestAt = null).duckAhead)
        // rules from before version 13 carry no date: any other commit counts
        val undated = HidingRulesStatus(parsed.copy(duckDetectorAt = ""), checkedAt = null)
        assertTrue(undated.copy(duckLatest = "a1b2c3d4", duckLatestAt = pinAt.minusSeconds(60).toString()).duckAhead)
    }

    @Test
    fun duckFeedParses() {
        // what deploy-site.yml writes from scripts/duck_nightly.sh
        val json = """{"sha":"41b35fec8838961c64437a90333c6bc49779a71c","tag":"nightly-2026-10-21-41b35fec","date":"2026-10-21T10:07:48Z","checked":"2026-10-21T12:34:44Z"}"""
        val checked = Instant.parse("2026-10-21T12:34:44Z")
        // asked 30 hours ago: may be shown for the rest of the 3 days, not 3 days from now
        assertEquals(
            DuckNightly("41b35fec8838961c64437a90333c6bc49779a71c", "2026-10-21T10:07:48Z", Duration.ofHours(42)),
            HidingRulesRepository.parseDuck(json, checked.plus(Duration.ofHours(30))),
        )
        // checked a little after the site's clock (runner and VPS differ): the full 3 days
        assertEquals(HidingRulesRepository.DUCK_MAX_AGE, HidingRulesRepository.parseDuck(json, checked.minus(Duration.ofMinutes(5))).freshFor)
        // the site kept an old answer because GitHub did not answer for days
        assertTrue(runCatching { HidingRulesRepository.parseDuck(json, checked.plus(HidingRulesRepository.DUCK_MAX_AGE).plusSeconds(1)) }.isFailure)
        // checked well after the site's own clock: not an answer to trust
        assertTrue(runCatching { HidingRulesRepository.parseDuck(json, checked.minus(Duration.ofHours(2))) }.isFailure)
        assertEquals("2026-10-21T10:07:48Z", HidingRulesRepository.parseDuck(json, checked.minus(Duration.ofMinutes(5))).date)
        // without a readable date the nightly cannot be compared with the pin
        for (date in listOf("\"\"", "\"yesterday\"", "null")) {
            val bad = json.replace("\"2026-10-21T10:07:48Z\"", date)
            assertTrue(date, runCatching { HidingRulesRepository.parseDuck(bad, checked) }.isFailure)
        }
        assertTrue(runCatching { HidingRulesRepository.parseDuck(json.replace(""""date":"2026-10-21T10:07:48Z",""", ""), checked) }.isFailure)
        assertTrue(runCatching { HidingRulesRepository.parseDuck(json.replace(""","checked":"2026-10-21T12:34:44Z"""", ""), checked) }.isFailure)
        assertTrue(runCatching { HidingRulesRepository.parseDuck("""{"sha":"","date":"x","checked":"2026-10-21T12:34:44Z"}""", checked) }.isFailure)
        assertTrue(runCatching { HidingRulesRepository.parseDuck("<html>404</html>", checked) }.isFailure)
    }
}
