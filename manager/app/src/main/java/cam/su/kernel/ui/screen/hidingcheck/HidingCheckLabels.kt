package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cam.su.kernel.R
import cam.su.kernel.data.model.HidingAudit

@Composable
internal fun findingTitle(finding: HidingAudit.Finding): String =
    findingTitleRes(finding.id)?.let { stringResource(it) } ?: finding.id

/** The title of a finding by id; null for one this app does not know. The module scan notification uses it too. */
fun findingTitleRes(id: String): Int? = when (id) {
    "moduleMounts" -> R.string.audit_module_mounts
    "camMounts" -> R.string.audit_cam_mounts
    "selinuxRules" -> R.string.audit_selinux_rules
    "maps" -> R.string.audit_maps
    "props" -> R.string.audit_props
    "bootArgs" -> R.string.audit_boot_args
    "lsposed" -> R.string.audit_lsposed
    "revanced" -> R.string.audit_revanced
    "customRom" -> R.string.audit_custom_rom
    "files" -> R.string.audit_files
    "selinux" -> R.string.audit_selinux
    "adb" -> R.string.audit_adb
    "adbStopped" -> R.string.audit_adb_stopped
    "appMounts" -> R.string.audit_app_mounts
    "appMaps" -> R.string.audit_app_maps
    "appSu" -> R.string.audit_app_su
    "appProps" -> R.string.audit_app_props
    "appSelinux" -> R.string.audit_app_selinux
    "appHooked" -> R.string.audit_app_hooked
    "appAttrTiming" -> R.string.audit_app_attr_timing
    "selinuxAvcLookups" -> R.string.audit_selinux_avc_lookups
    "profileUmount" -> R.string.audit_profile_umount
    "defaultProfileUmount" -> R.string.audit_default_profile_umount
    else -> null
}

/** What to do about [finding]; [fixOn]: its fix is on and the finding is still there. */
internal fun fixLabel(finding: HidingAudit.Finding, fixOn: Boolean): Int = when {
    finding.fix != null && fixOn -> R.string.hiding_fix_on_still
    finding.id == "profileUmount" -> R.string.hiding_fix_profile
    finding.id == "defaultProfileUmount" -> R.string.hiding_fix_default_profile
    finding.id == "appHooked" -> R.string.hiding_fix_hooked
    finding.id == "appAttrTiming" -> R.string.hiding_fix_attr_timing
    finding.id == "selinuxAvcLookups" -> R.string.hiding_fix_avc_lookups
    finding.fix == null -> adviceRes(finding) ?: R.string.audit_fix_none
    else -> fixLabel(finding.fix)
}

/** What the user can do about a finding the page cannot switch off; null when there is no advice. */
internal fun adviceRes(finding: HidingAudit.Finding): Int? = when (finding.id) {
    "moduleMounts" -> R.string.hiding_advice_module_mounts
    "maps", "appMaps" -> R.string.hiding_advice_maps
    "bootArgs" -> R.string.hiding_advice_boot_args
    "lsposed" -> R.string.hiding_advice_lsposed
    "revanced" -> R.string.hiding_advice_revanced
    "customRom" -> R.string.hiding_advice_custom_rom
    "files" -> R.string.hiding_advice_files
    "selinux", "appSelinux" -> R.string.hiding_advice_selinux
    "adb" -> R.string.hiding_advice_adb
    "adbStopped" -> R.string.hiding_advice_adb_stopped
    "appSu" -> R.string.hiding_advice_app_su
    else -> null
}

/** The label of the button that applies [action] to [finding]. */
@Composable
internal fun actionLabel(action: String, finding: HidingAudit.Finding, state: HidingCheckUiState): String = when (action) {
    HidingCheckUiState.APP_UMOUNT -> if (state.app != null || finding.items.size <= 1) {
        stringResource(R.string.hiding_action_umount_app)
    } else {
        stringResource(R.string.hiding_action_umount_apps, finding.items.size)
    }
    HidingCheckUiState.DEFAULT_UMOUNT -> stringResource(R.string.hiding_fix_default_profile)
    else -> stringResource(fixLabel(action))
}

internal fun passedTitle(id: String): Int = when (id) {
    "appMounts" -> R.string.hiding_ok_app_mounts
    "appMaps" -> R.string.hiding_ok_app_maps
    "appSu" -> R.string.hiding_ok_app_su
    "appProps" -> R.string.hiding_ok_app_props
    "appSelinux" -> R.string.hiding_ok_app_selinux
    "appHooked" -> R.string.hiding_ok_app_hooked
    "appAttrTiming" -> R.string.hiding_ok_app_attr_timing
    "selinuxAvcLookups" -> R.string.hiding_ok_selinux_avc_lookups
    else -> R.string.hiding_ok_profile_umount
}

internal fun fixLabel(fix: String?): Int = when (fix) {
    "kernelUmount" -> R.string.audit_fix_kernel_umount
    "selinuxHide" -> R.string.audit_fix_selinux_hide
    "hideBootloader" -> R.string.audit_fix_hide_bootloader
    else -> R.string.audit_fix_none
}
