#[cfg(target_os = "android")]
mod android {
    use const_format::concatcp;
    use std::time::Duration;

    pub const BOOT_STAGE_TIMEOUT: Duration = Duration::from_secs(35);
    pub const HOT_INSTALL_SCRIPT_TIMEOUT: Duration = Duration::from_secs(30);
    pub const EMULATED_SOFT_REBOOT_TIMEOUT: Duration = Duration::from_secs(5);
    pub const WAITSYS_READY_TIMEOUT: Duration = Duration::from_secs(2);
    pub const WAITSYS_STOP_TIMEOUT: Duration = Duration::from_secs(5);
    pub const BOOTLOG_TIMEOUT: &str = "30s";

    pub const ADB_DIR: &str = "/data/adb/";
    pub const WORKING_DIR: &str = concatcp!(ADB_DIR, "cam/");
    pub const BINARY_DIR: &str = concatcp!(WORKING_DIR, "bin/");
    pub const LIBRARY_DIR: &str = concatcp!(WORKING_DIR, "lib/");
    pub const LOG_DIR: &str = concatcp!(WORKING_DIR, "log/");
    pub const SULOGD_LOCK_PATH: &str = concatcp!(WORKING_DIR, "sulogd.lock");
    pub const BOOT_GUARD_PATH: &str = concatcp!(WORKING_DIR, "bootguard.json");
    /// boot_id of the boot that last ran the service stage
    pub const SERVICES_STARTED_PATH: &str = concatcp!(WORKING_DIR, ".services_started");
    pub const HIDE_BOOTLOADER_FLAG: &str = concatcp!(WORKING_DIR, ".hide_bootloader");
    /// the installed copy of a module, kept when an update replaces it
    pub const MODULE_BACKUP_DIR: &str = concatcp!(WORKING_DIR, "module_backup/");

    pub const PROFILE_DIR: &str = concatcp!(WORKING_DIR, "profile/");
    pub const PROFILE_SELINUX_DIR: &str = concatcp!(PROFILE_DIR, "selinux/");
    pub const PROFILE_TEMPLATE_DIR: &str = concatcp!(PROFILE_DIR, "templates/");

    pub const KSURC_PATH: &str = concatcp!(WORKING_DIR, ".camrc");
    pub const DAEMON_PATH: &str = concatcp!(ADB_DIR, "camd");
    pub const LIBADBROOT_PATH: &str = concatcp!(LIBRARY_DIR, "libadbroot.so");

    pub const DAEMON_LINK_PATH: &str = concatcp!(BINARY_DIR, "camd");

    pub const MODULE_DIR: &str = concatcp!(ADB_DIR, "modules/");
    pub const MODULE_UPDATE_DIR: &str = concatcp!(ADB_DIR, "modules_update/");
    pub const METAMODULE_DIR: &str = concatcp!(ADB_DIR, "metamodule/");
    // Replaced module dirs are parked here until the next boot
    pub const HOT_INSTALL_TRASH_DIR: &str = concatcp!(WORKING_DIR, "hot_install_trash/");

    // Preferred first: /metadata/vold (root:root 0700) hides best and does not
    // bump /metadata's nlink; else /metadata/watchdog (like Magisk); else /metadata.
    pub const PREINIT_DIR_VOLD: &str = "/metadata/vold/cam/";
    pub const PREINIT_DIR_WATCHDOG: &str = "/metadata/watchdog/cam/";
    pub const PREINIT_DIR_DEFAULT: &str = "/metadata/cam/";
    pub const METADATA_FILE_CON: &str = "u:object_r:metadata_file:s0";
    pub const VOLD_METADATA_FILE_CON: &str = "u:object_r:vold_metadata_file:s0";
    pub const MODULES_RC_FILE: &str = "modules.rc";
    pub const MODULES_RC_TMP_FILE: &str = ".modules.rc.tmp";
    // Module preinit dirs are synced to PREINIT_DIR_*/preinit/<id>/ and run by the kernel's runner
    pub const PREINIT_SUBDIR: &str = "preinit";
    pub const PREINIT_MANIFEST_FILE: &str = ".manifest";
    pub const PREINIT_BOOTCOUNT_FILE: &str = ".preinit_bootcount";
    pub const PREINIT_STATUS_PATH: &str = concatcp!(WORKING_DIR, "preinit_status");

    pub const MODULE_WEB_DIR: &str = "webroot";
    pub const MODULE_ACTION_SH: &str = "action.sh";
    pub const DISABLE_FILE_NAME: &str = "disable";
    pub const UPDATE_FILE_NAME: &str = "update";
    pub const REMOVE_FILE_NAME: &str = "remove";
    pub const MODULE_INIT_RC_DIR: &str = "initrc";
    pub const MODULE_PREINIT_DIR: &str = "preinit";
    pub const PREINIT_CONF: &str = "preinit.conf";
    // Written by installer.sh when customize.sh requests a hot install
    pub const HOT_INSTALL_MARKER: &str = ".ksu_hot_install";

    // Module config system
    pub const MODULE_CONFIG_DIR: &str = concatcp!(WORKING_DIR, "module_configs/");
    pub const PERSIST_CONFIG_NAME: &str = "persist.config";
    pub const TEMP_CONFIG_NAME: &str = "tmp.config";

    // Metamodule support
    pub const METAMODULE_MOUNT_SCRIPT: &str = "metamount.sh";
    pub const METAMODULE_METAINSTALL_SCRIPT: &str = "metainstall.sh";
    pub const METAMODULE_METAUNINSTALL_SCRIPT: &str = "metauninstall.sh";

    pub const KSU_BACKUP_DIR: &str = WORKING_DIR;
    pub const KSU_BACKUP_FILE_PREFIX: &str = "cam_backup_";
    pub const BACKUP_FILENAME: &str = "stock_image.sha1";
    pub const KSU_TEMP_BACKUP_DIR_NAME: &str = "boot_backup";
    // What a partition held before the last flash, one file per partition (Cam)
    pub const PREVIOUS_IMAGE_DIR: &str = concatcp!(WORKING_DIR, "previous/");

    pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");
}

#[allow(unused)]
pub const VERSION_CODE: &str = env!("VERSION_CODE");
pub const VERSION_NAME: &str = env!("VERSION_NAME");

/// Kept whole in the binary: the Manager build looks for this text in the libcamd.so it packs
/// (checkCamdSource in manager/app/build.gradle.kts) to warn about a camd built from older sources.
const SOURCE_ID_MARKER: &str = concat!("CAMD_SOURCE_ID=", env!("CAMD_SOURCE_ID"), ";");

/// The git blob hash of the committed `userspace/`, `uapi/` and Cargo files listing (build.rs).
pub fn source_id() -> &'static str {
    // read through a black box so the whole marker stays in the binary
    let marker = std::hint::black_box(SOURCE_ID_MARKER);
    marker
        .strip_prefix("CAMD_SOURCE_ID=")
        .and_then(|rest| rest.strip_suffix(';'))
        .unwrap_or(marker)
}
#[cfg(target_os = "android")]
pub const FULL_VERSION: &str = const_format::formatcp!(
    "{VERSION_NAME} (uapi: {})",
    crate::ksu_uapi::KERNEL_SU_UAPI_VERSION
);

#[cfg(target_os = "android")]
pub use android::*;
