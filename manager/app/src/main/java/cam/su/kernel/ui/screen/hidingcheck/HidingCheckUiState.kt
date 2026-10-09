package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.runtime.Immutable
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingRulesStatus
import cam.su.kernel.ui.util.AttestationReport

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

/** A check that ran and found nothing; [rootCount] is what camd saw for comparison, when it counts. */
@Immutable
data class PassedCheck(val id: String, val rootCount: Int? = null)

/** A KernelSU feature or camd setting the page can switch, as the audit names it in [HidingAudit.Finding.fix]. */
@Immutable
data class HidingFix(
    val enabled: Boolean = false,
    /** false when the kernel lacks it, or a module manages it */
    val available: Boolean = false,
)

/** The one app the page checks (from its App Profile), instead of the whole device. */
@Immutable
data class CheckedApp(
    val uid: Int,
    val label: String,
    val packageName: String,
    /** null until a scan; false when none of its processes was running */
    val running: Boolean? = null,
)

@Immutable
data class HidingCheckUiState(
    /** null when the page checks the whole device */
    val app: CheckedApp? = null,
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
    /** checks of the last scan that found nothing */
    val passed: List<PassedCheck> = emptyList(),
    val kernelUmount: HidingFix = HidingFix(),
    val selinuxHide: HidingFix = HidingFix(),
    val hideBootloader: HidingFix = HidingFix(),
    /** some applied fixes, or disabled modules, take full effect after a reboot */
    val rebootNeeded: Boolean = false,
    /** module id to its name, for findings that name modules */
    val moduleNames: Map<String, String> = emptyMap(),
    /** modules turned off from this page */
    val disabledModules: Set<String> = emptySet(),
    /** the rules the app view uses and how their last update check went; null until read */
    val rules: HidingRulesStatus? = null,
    val checkingRules: Boolean = false,
    /** the key attestation, once the user asked for it */
    val attestation: AttestationReport? = null,
    val checkingAttestation: Boolean = false,
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

    fun moduleName(id: String): String = moduleNames[id]?.takeIf { it.isNotBlank() } ?: id

    companion object {
        /** App view checks, which report only what they find: the ones missing from a scan passed. */
        private val APP_CHECKS = listOf("appMounts", "appMaps", "appSu", "appProps", "appSelinux")

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

        /** The checks [audit] ran that found nothing, from what its stats say ran. */
        fun passed(audit: HidingAudit, ignored: Set<String>): List<PassedCheck> {
            val found = audit.findings.mapTo(mutableSetOf()) { it.id } + ignored
            val stats = audit.stats
            val ran = buildList {
                if (stats["appView"] == 1) addAll(APP_CHECKS)
                if ((stats["processes"] ?: 0) > 0) addAll(listOf("appMounts", "appMaps"))
                if (stats["appNative"] == 1) add("appHooked")
                if (stats["profileChecked"] == 1 && "defaultProfileUmount" !in found) add("profileUmount")
            }.distinct()
            return ran.filter { it !in found }.map { id ->
                PassedCheck(id, if (id == "appMounts") stats["rootModuleMounts"] else null)
            }
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
    val onDisableModule: (id: String) -> Unit,
    val onCheckAttestation: () -> Unit,
    val onLaunchApp: () -> Unit,
)
