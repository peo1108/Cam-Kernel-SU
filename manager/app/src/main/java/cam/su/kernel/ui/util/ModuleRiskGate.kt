package cam.su.kernel.ui.util

import cam.su.kernel.R
import cam.su.kernel.camApp
import cam.su.kernel.data.repository.HidingRulesRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.hiding.ModuleRisk
import cam.su.kernel.hiding.ModuleRiskHit
import cam.su.kernel.hiding.ModuleRiskScanner
import java.io.File

// Cam-only, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges stay small.

/** The signed rules' `moduleRisks` as they are now (bundled, or a newer verified download). */
fun moduleRiskScanner(): ModuleRiskScanner = ModuleRiskScanner(HidingRulesRepository().current().moduleRisks)

/** What [moduleRiskGate] finds in a zip: [block] when it would stop the install. */
class ModuleRiskCheck(val hit: ModuleRiskHit, val block: Boolean)

/** The risk gate's verdict on [zip], without printing or stopping anything. Blocking. */
fun checkModuleRisk(zip: File): ModuleRiskCheck? {
    val repo = HidingRulesRepository()
    repo.refreshRules()
    val hit = ModuleRiskScanner(repo.current().moduleRisks).scanZip(zip) ?: return null
    return ModuleRiskCheck(hit, hit.risk.level == ModuleRisk.Level.Block && SettingsRepositoryImpl().moduleRiskBlock)
}

/**
 * Checks a module zip before camd installs it. Prints what was found; returns the failed result
 * when the install must stop (a `block` entry while blocking is on), null to go on. Blocking.
 */
fun moduleRiskGate(zip: File, onStdout: (String) -> Unit): FlashResult? {
    val check = checkModuleRisk(zip) ?: return null
    val hit = check.hit
    val block = check.block
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
