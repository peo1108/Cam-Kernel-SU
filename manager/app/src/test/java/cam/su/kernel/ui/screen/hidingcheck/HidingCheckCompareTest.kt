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

    /** 1.1.9 kept " (deleted)" and named a mapping from its first '/' */
    @Test
    fun appMapsSavedBefore120StillCompare() {
        val previous = audit(finding("appMaps", "/memfd:zygisk-loader (deleted)", "/data/adb/x.dex]", appView = true))
        val current = audit(
            finding(
                "appMaps",
                "/memfd:zygisk-loader",
                "[anon:dalvik-classes.dex extracted in memory from /data/adb/x.dex]",
                "/data/adb/y.so",
                appView = true,
            ),
        )
        val (checked, _, _) = HidingCheckUiState.compare(current, previous, emptySet())
        assertEquals(setOf("/data/adb/y.so"), checked[0].newItems)
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
            .copy(stats = mapOf("appView" to 1, "rootModuleMounts" to 4))
            .let { it.copy(findings = it.findings.map { f -> f.copy(modules = listOf("zygisk")) }) }
        assertEquals(original, HidingAudit.parse(original.toJson().toString()))
    }

    @Test
    fun passedChecksAreTheOnesThatRan() {
        val scan = audit(finding("appMaps", "/data/adb/x.so", appView = true))
            .copy(stats = mapOf("appView" to 1, "appNative" to 0, "rootModuleMounts" to 7, "profileChecked" to 1))
        val passed = HidingCheckUiState.passed(scan, ignored = setOf("appSu"))
        assertEquals(listOf("appMounts", "appProps", "appSelinux", "profileUmount"), passed.map { it.id })
        assertEquals(7, passed.first().rootCount)
        // nothing ran, nothing passed
        assertEquals(emptyList<PassedCheck>(), HidingCheckUiState.passed(audit(), emptySet()))
    }

    @Test
    fun avcLookupPassesOnlyWhenClassified() {
        val ran = audit().copy(stats = mapOf("avcLookup" to 1, "avcLookupsA" to 100, "avcLookupsB" to 100))
        assertEquals(listOf("selinuxAvcLookups"), HidingCheckUiState.passed(ran, emptySet()).map { it.id })
        // the probe answered without counts, or found extra lookups
        assertEquals(emptyList<PassedCheck>(), HidingCheckUiState.passed(audit().copy(stats = mapOf("avcLookup" to 0)), emptySet()))
        val extra = audit(finding("selinuxAvcLookups", "A/B = 1/2")).copy(stats = mapOf("avcLookup" to 1))
        assertEquals(emptyList<PassedCheck>(), HidingCheckUiState.passed(extra, emptySet()))
    }

    @Test
    fun mergedAuditsKeepEveryStat() {
        val merged = audit(finding("maps", "a")).copy(stats = mapOf("rootModuleMounts" to 2)) +
                audit().copy(stats = mapOf("appView" to 1))
        assertEquals(mapOf("rootModuleMounts" to 2, "appView" to 1), merged.stats)
        assertEquals(1, merged.fixable)
    }

    @Test
    fun eachFindingOffersTheFixThatIsStillOff() {
        val state = HidingCheckUiState(kernelUmount = HidingFix(enabled = false, available = true), selinuxHide = HidingFix(enabled = true, available = true))
        fun f(id: String, fix: String?) = HidingAudit.Finding(id = id, leak = true, items = emptyList(), fix = fix)
        assertEquals("kernelUmount", state.actionFor(f("camMounts", "kernelUmount")))
        // on already, or the kernel lacks it: nothing to tap
        assertEquals(null, state.actionFor(f("selinuxRules", "selinuxHide")))
        assertEquals(null, state.actionFor(f("props", "hideBootloader")))
        assertEquals(HidingCheckUiState.APP_UMOUNT, state.actionFor(f("profileUmount", null)))
        assertEquals(HidingCheckUiState.DEFAULT_UMOUNT, state.actionFor(f("defaultProfileUmount", null)))
        assertEquals(null, state.actionFor(f("lsposed", null)))

        val withFindings = state.copy(findings = listOf("camMounts" to "kernelUmount", "appMounts" to "kernelUmount", "profileUmount" to null).map { (id, fix) ->
            CheckedFinding(f(id, fix), null, emptySet())
        })
        assertEquals(setOf("kernelUmount", HidingCheckUiState.APP_UMOUNT), withFindings.pendingFixes)
    }
}
