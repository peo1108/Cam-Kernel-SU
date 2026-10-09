package cam.su.kernel.ui.screen.hidingcheck

import cam.su.kernel.data.model.HidingAudit
import org.junit.Assert.assertEquals
import org.junit.Test

class HidingCheckCompareTest {

    private fun finding(id: String, vararg items: String, appView: Boolean = false) =
        HidingAudit.Finding(id = id, leak = true, items = items.toList(), fix = "kernelUmount", appView = appView)

    private fun audit(vararg findings: HidingAudit.Finding) = HidingAudit(findings.toList(), findings.size)

    @Test
    fun firstScanMarksNothing() {
        val (checked, gone, _) = HidingCheckUiState.compare(audit(finding("maps", "a")), null, emptySet())
        assertEquals(listOf<FindingChange?>(null), checked.map { it.change })
        assertEquals(emptyList<HidingAudit.Finding>(), gone)
    }

    @Test
    fun marksNewSameAndGone() {
        val previous = audit(finding("maps", "a"), finding("props", "x"))
        val current = audit(finding("maps", "a", "b"), finding("appMounts", "/system/bin", appView = true))
        val (checked, gone, _) = HidingCheckUiState.compare(current, previous, emptySet())
        assertEquals(listOf(FindingChange.SAME, FindingChange.NEW), checked.map { it.change })
        assertEquals(setOf("b"), checked[0].newItems)
        assertEquals(listOf("props"), gone.map { it.id })
    }

    @Test
    fun ignoredFindingsStayAside() {
        val previous = audit(finding("props", "x"))
        val current = audit(finding("maps", "a"))
        val (checked, gone, ignored) = HidingCheckUiState.compare(current, previous, setOf("maps", "props"))
        assertEquals(emptyList<CheckedFinding>(), checked)
        assertEquals(emptyList<HidingAudit.Finding>(), gone)
        assertEquals(listOf("maps"), ignored.map { it.id })
    }

    @Test
    fun savedAuditReadsBack() {
        val original = audit(finding("maps", "a"), finding("appSu", "/system/bin/su", appView = true))
        assertEquals(original, HidingAudit.parse(original.toJson().toString()))
    }
}
