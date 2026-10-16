package cam.su.kernel.ui.util

import cam.su.kernel.R
import cam.su.kernel.camApp
import cam.su.kernel.data.repository.HidingRulesRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.hiding.ModuleRisk
import cam.su.kernel.hiding.ModuleRiskScanner
import java.io.File

// Cam-only, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges stay small.

/** The signed rules' `moduleRisks` as they are now (bundled, or a newer verified download). */
fun moduleRiskScanner(): ModuleRiskScanner = ModuleRiskScanner(HidingRulesRepository().current().moduleRisks)

/**
 * Checks a module zip before camd installs it. Prints what was found; returns the failed result
 * when the install must stop (a `block` entry while blocking is on), null to go on. Blocking.
 */
fun moduleRiskGate(zip: File, onStdout: (String) -> Unit): FlashResult? {
    val repo = HidingRulesRepository()
    repo.refreshRules()
    val hit = ModuleRiskScanner(repo.current().moduleRisks).scanZip(zip) ?: return null
    val block = hit.risk.level == ModuleRisk.Level.Block && SettingsRepositoryImpl().moduleRiskBlock
    val lines = buildList {
        add("")
        add(camApp.getString(if (block) R.string.flash_risk_blocked else R.string.flash_risk_warn))
        add(camApp.getString(R.string.flash_risk_reason, hit.risk.reason()))
        if (hit.risk.source.isNotBlank()) add(camApp.getString(R.string.flash_risk_source, hit.risk.source))
        add(camApp.getString(R.string.flash_risk_found_in, hit.where))
        if (block) add(camApp.getString(R.string.flash_risk_override))
        add("")
    }
    lines.forEach(onStdout)
    return if (block) FlashResult(1, camApp.getString(R.string.flash_risk_blocked), false) else null
}
