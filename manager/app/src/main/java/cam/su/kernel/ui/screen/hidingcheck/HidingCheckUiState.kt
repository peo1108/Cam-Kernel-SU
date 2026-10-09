package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.runtime.Immutable
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingRulesStatus

/** How a finding compares with the scan before it. */
enum class FindingChange { NEW, SAME }

@Immutable
data class CheckedFinding(
    val finding: HidingAudit.Finding,
    /** null on the first scan */
    val change: FindingChange?,
    /** items the scan before did not have */
    val newItems: Set<String>,
)

/** A KernelSU feature or camd setting the page can switch, as the audit names it in [HidingAudit.Finding.fix]. */
@Immutable
data class HidingFix(
    val enabled: Boolean = false,
    /** false when the kernel lacks it, or a module manages it */
    val available: Boolean = false,
)

@Immutable
data class HidingCheckUiState(
    val scanning: Boolean = false,
    /** camd could not run the last audit (camd missing, no root) */
    val rootViewFailed: Boolean = false,
    /** the isolated probe did not answer */
    val appViewFailed: Boolean = false,
    /** null when there was never a scan */
    val scanTime: Long? = null,
    /** the scan [findings] were compared with; null when there was none */
    val previousTime: Long? = null,
    /** findings to act on, ignored ones left out */
    val findings: List<CheckedFinding> = emptyList(),
    /** in the scan before, gone now */
    val gone: List<HidingAudit.Finding> = emptyList(),
    val ignored: List<HidingAudit.Finding> = emptyList(),
    val kernelUmount: HidingFix = HidingFix(),
    val selinuxHide: HidingFix = HidingFix(),
    val hideBootloader: HidingFix = HidingFix(),
    /** some applied fixes take full effect after a reboot */
    val rebootNeeded: Boolean = false,
    /** the rules the app view uses and how their last update check went; null until read */
    val rules: HidingRulesStatus? = null,
    val checkingRules: Boolean = false,
) {
    val leaks: Int
        get() = findings.count { it.finding.leak }

    val newCount: Int
        get() = findings.count { it.change == FindingChange.NEW }

    /** fixes the visible findings ask for that are still off */
    val pendingFixes: Set<String>
        get() = findings.mapNotNull { it.finding.fix }.filterTo(mutableSetOf()) { !fix(it).enabled && fix(it).available }

    fun fix(name: String): HidingFix = when (name) {
        "kernelUmount" -> kernelUmount
        "selinuxHide" -> selinuxHide
        "hideBootloader" -> hideBootloader
        else -> HidingFix()
    }

    /** how many visible findings [name] would fix */
    fun fixes(name: String): Int = findings.count { it.finding.fix == name }

    companion object {
        /**
         * [current] against [previous]: what is new, what stays, and what is gone.
         * Without a previous scan nothing is marked.
         */
        fun compare(
            current: HidingAudit,
            previous: HidingAudit?,
            ignored: Set<String>,
        ): Triple<List<CheckedFinding>, List<HidingAudit.Finding>, List<HidingAudit.Finding>> {
            val before = previous?.findings?.associateBy { it.id }
            val checked = current.findings.filter { it.id !in ignored }.map { f ->
                val old = before?.get(f.id)
                CheckedFinding(
                    finding = f,
                    change = when {
                        before == null -> null
                        old == null -> FindingChange.NEW
                        else -> FindingChange.SAME
                    },
                    newItems = if (old == null) emptySet() else f.items.toSet() - old.items.toSet(),
                )
            }
            val now = current.findings.mapTo(mutableSetOf()) { it.id }
            val gone = previous?.findings.orEmpty().filter { it.id !in now && it.id !in ignored }
            val ignoredNow = current.findings.filter { it.id in ignored }
            return Triple(checked, gone, ignoredNow)
        }
    }
}

@Immutable
data class HidingCheckActions(
    val onBack: () -> Unit,
    val onScan: () -> Unit,
    val onApplyFixes: () -> Unit,
    val onSetFix: (name: String, enabled: Boolean) -> Unit,
    val onIgnore: (id: String) -> Unit,
    val onRestore: (id: String) -> Unit,
    val onClearHistory: () -> Unit,
    val onCheckUpdates: () -> Unit,
)
