use crate::boot_guard::{self, Decision};
use crate::module::{handle_updated_modules, prune_modules};
use crate::utils::is_safe_mode;
use crate::{
    assets, defs, ksucalls, metamodule, restorecon,
    utils::{self},
};
use anyhow::{Context, Result};
use log::{error, info, warn};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn on_post_data_fs() -> Result<()> {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_post_fs_data");
        return Ok(());
    }

    ksucalls::report_post_fs_data();

    utils::umask(0);

    // Clear all temporary module configs early
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    #[cfg(unix)]
    let _ = catch_bootlog("logcat", &["logcat", "-b", "all"]);
    #[cfg(unix)]
    let _ = catch_bootlog("dmesg", &["dmesg", "-w", "-r"]);

    if utils::has_magisk() {
        warn!("Magisk detected, skip post-fs-data!");
        return Ok(());
    }

    let safe_mode = crate::utils::is_safe_mode();

    if safe_mode {
        // we should still ensure module directory exists in safe mode
        // because we may need to operate the module dir in safe mode
        warn!("safe mode, skip common post-fs-data.d scripts");
    } else {
        // Then exec common post-fs-data scripts
        if let Err(e) = crate::module::exec_common_scripts("post-fs-data.d", true) {
            warn!("exec common post-fs-data scripts failed: {e}");
        }
    }

    let module_dir = defs::MODULE_DIR;

    assets::ensure_binaries(true).with_context(|| "Failed to extract bin assets")?;

    // if we are in safe mode, we should disable all modules
    if safe_mode {
        warn!("safe mode, skip post-fs-data scripts and disable all modules!");
        if let Err(e) = crate::module::disable_all_modules() {
            warn!("disable all modules failed: {e}");
        }
        return Ok(());
    }

    let updated = handle_updated_modules().unwrap_or_else(|e| {
        warn!("handle updated modules failed: {e}");
        Vec::new()
    });

    // before prune and the preinit rc refresh, so a disabled module stays out of both
    run_boot_guard(&updated);

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    // Refresh /metadata/watchdog/ksu/modules.rc so the next boot's kernel hook sees the
    // current module set. Acts as a safety net when state was changed outside
    // of ksud's normal mutation commands.
    if let Err(e) = crate::module::regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // load sepolicy.rule
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // load feature config
    if is_safe_mode() {
        warn!("safe mode, skip load feature config");
    } else if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // SUSFS settings from the Manager, before module scripts so a module can still override them
    crate::susfs::apply_stage(crate::susfs_config::Stage::PostFsData);

    // execute metamodule post-fs-data script first (priority)
    if let Err(e) = metamodule::exec_stage_script("post-fs-data", true) {
        warn!("exec metamodule post-fs-data script failed: {e}");
    }

    // exec modules post-fs-data scripts
    // TODO: Add timeout
    if let Err(e) = crate::module::exec_stage_script("post-fs-data", true) {
        warn!("exec post-fs-data scripts failed: {e}");
    }

    // load system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // after system.prop, so a module cannot put a revealing value back
    crate::hide_bootloader::apply_if_enabled();

    // execute metamodule mount script
    if let Err(e) = metamodule::exec_mount_script(module_dir) {
        warn!("execute metamodule mount failed: {e}");
    }

    run_stage("post-mount", true);

    std::env::set_current_dir("/").with_context(|| "failed to chdir to /")?;

    Ok(())
}

fn run_boot_guard(updated: &[String]) {
    let path = Path::new(defs::BOOT_GUARD_PATH);
    let mut state = boot_guard::load(path);
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    let decision = boot_guard::on_boot_start(
        &mut state,
        updated,
        &crate::module::enabled_module_ids(),
        now,
    );
    // persist the count before touching modules, so a crash below still counts
    if let Err(e) = boot_guard::save(path, &state) {
        warn!("boot guard: save state failed: {e}");
    }
    if let Decision::Disable(ids) = decision {
        warn!(
            "boot guard: boot did not complete {} times, disabling {ids:?}",
            state.threshold
        );
        for id in &ids {
            if let Err(e) = crate::module::disable_module(id) {
                warn!("boot guard: disable {id} failed: {e}");
            }
        }
    }
}

/// Posts a system notification as the shell user: notifications from uid 0 are dropped.
fn notify_boot_guard(ids: &[String]) {
    use std::os::unix::process::CommandExt;

    let vietnamese = ["persist.sys.locale", "ro.product.locale"]
        .iter()
        .find_map(|prop| utils::getprop(prop).filter(|v| !v.is_empty()))
        .is_some_and(|locale| locale.starts_with("vi"));
    let (title, body) = boot_guard::notice_text(ids, vietnamese);
    // the notification service can still be starting right after boot-completed
    for attempt in 0..3 {
        let posted = std::process::Command::new("cmd")
            .args(["notification", "post", "-S", "bigtext", "-t", &title])
            .args(["su_kernel_boot_guard", &body])
            .uid(2000)
            .gid(2000)
            .stdout(std::process::Stdio::null())
            .status()
            .is_ok_and(|status| status.success());
        if posted {
            info!("boot guard: notified about {ids:?}");
            return;
        }
        if attempt < 2 {
            std::thread::sleep(std::time::Duration::from_secs(5));
        }
    }
    warn!("boot guard: could not post the notification");
}

pub fn run_stage(stage: &str, block: bool) {
    utils::umask(0);

    if utils::has_magisk() {
        warn!("Magisk detected, skip {stage}");
        return;
    }

    if crate::utils::is_safe_mode() {
        warn!("safe mode, skip {stage} scripts");
        return;
    }

    if let Err(e) = crate::module::exec_common_scripts(&format!("{stage}.d"), block) {
        warn!("Failed to exec common {stage} scripts: {e}");
    }

    // execute metamodule stage script first (priority)
    if let Err(e) = metamodule::exec_stage_script(stage, block) {
        warn!("Failed to exec metamodule {stage} script: {e}");
    }

    // execute regular modules stage scripts
    if let Err(e) = crate::module::exec_stage_script(stage, block) {
        warn!("Failed to exec {stage} scripts: {e}");
    }
}

pub fn on_services() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_services");
        return;
    }

    info!("on_services triggered!");
    crate::susfs::apply_stage(crate::susfs_config::Stage::Service);
    run_stage("service", false);
}

pub fn on_boot_completed() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_boot_completed");
        return;
    }

    ksucalls::report_boot_complete();
    info!("on_boot_completed triggered!");

    // the framework sets some of them (sys.oem_unlock_allowed) once it is up
    crate::hide_bootloader::apply_if_enabled();

    let path = Path::new(defs::BOOT_GUARD_PATH);
    let mut state = boot_guard::load(path);
    boot_guard::on_boot_completed(&mut state);
    let notice = boot_guard::take_notice(&mut state);
    if let Err(e) = boot_guard::save(path, &state) {
        warn!("boot guard: save state failed: {e}");
    }
    if let Some(ids) = notice {
        notify_boot_guard(&ids);
    }

    run_stage("boot-completed", false);

    // forks: it waits for storage before the sus paths
    crate::susfs::apply_stage(crate::susfs_config::Stage::BootCompleted);
}

#[cfg(unix)]
fn catch_bootlog(logname: &str, command: &[&str]) -> Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::Stdio;

    let logdir = Path::new(defs::LOG_DIR);
    utils::ensure_dir_exists(logdir)?;
    let bootlog = logdir.join(format!("{logname}.log"));
    let oldbootlog = logdir.join(format!("{logname}.old.log"));

    if bootlog.exists() {
        std::fs::rename(&bootlog, oldbootlog)?;
    }

    let bootlog = std::fs::File::create(bootlog)?;

    let mut args = vec!["-s", "9", "30s"];
    args.extend_from_slice(command);
    // timeout -s 9 30s logcat > boot.log
    let result = unsafe {
        std::process::Command::new("timeout")
            .process_group(0)
            .pre_exec(|| {
                utils::switch_cgroups();
                Ok(())
            })
            .args(args)
            .stdout(Stdio::from(bootlog))
            .spawn()
    };

    if let Err(e) = result {
        warn!("Failed to start logcat: {e:#}");
    }

    Ok(())
}
