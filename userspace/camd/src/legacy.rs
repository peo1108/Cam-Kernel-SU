//! Moves data left by builds that still used the KernelSU names over to the
//! Cam paths. While the running kernel is still a KernelSU one (it execs
//! /data/adb/ksud and reads /data/adb/ksu), links are left at the old names;
//! once a kernel with the cam domain runs, those links are removed again.

use std::fs;
use std::io::ErrorKind;
use std::os::unix::fs::symlink;
use std::path::Path;

use log::{info, warn};

use crate::{defs, restorecon};

const LEGACY_WORKING_DIR: &str = "/data/adb/ksu";
const LEGACY_DAEMON_PATH: &str = "/data/adb/ksud";
const LEGACY_PREINIT_DIRS: [(&str, &str); 2] = [
    ("/metadata/ksu", "/metadata/cam"),
    ("/metadata/watchdog/ksu", "/metadata/watchdog/cam"),
];
const LEGACY_RC_NAME: &str = ".ksurc";
const LEGACY_BACKUP_PREFIX: &str = "ksu_backup_";

/// Safe to call on every start: once the data is moved it only adds or drops the links.
pub fn migrate() {
    let keep_links = !restorecon::kernel_has_cam_domain();
    let working_dir = defs::WORKING_DIR.trim_end_matches('/');
    move_and_link(LEGACY_WORKING_DIR, working_dir, keep_links);
    rename_legacy_files(working_dir);
    for (old, new) in LEGACY_PREINIT_DIRS {
        move_and_link(old, new, keep_links);
    }
    move_and_link(LEGACY_DAEMON_PATH, defs::DAEMON_PATH, keep_links);
}

fn move_and_link(old: &str, new: &str, keep_link: bool) {
    let old_meta = match fs::symlink_metadata(old) {
        Ok(meta) => Some(meta),
        Err(e) if e.kind() == ErrorKind::NotFound => None,
        Err(e) => {
            warn!("legacy: stat {old}: {e}");
            return;
        }
    };

    let target = Path::new(new).file_name().unwrap_or_default();
    if let Some(meta) = old_meta {
        if meta.file_type().is_symlink() {
            // only drop a link we made, never one the user pointed elsewhere
            if !keep_link && fs::read_link(old).is_ok_and(|t| t.as_os_str() == target) {
                match fs::remove_file(old) {
                    Ok(()) => info!("legacy: removed link {old}"),
                    Err(e) => warn!("legacy: remove link {old}: {e}"),
                }
            }
            return;
        }
        if let Err(e) = move_entry(old, new, meta.is_dir()) {
            warn!("legacy: move {old} -> {new}: {e}");
            return;
        }
    }

    if !keep_link || !Path::new(new).exists() || fs::symlink_metadata(old).is_ok() {
        return;
    }
    match symlink(target, old) {
        Ok(()) => {
            let _ = restorecon::set_su_file_con(old);
            info!("legacy: linked {old} -> {new}");
        }
        Err(e) => warn!("legacy: link {old} -> {new}: {e}"),
    }
}

fn move_entry(old: &str, new: &str, is_dir: bool) -> std::io::Result<()> {
    if !Path::new(new).exists() {
        fs::rename(old, new)?;
        info!("legacy: moved {old} -> {new}");
        return Ok(());
    }
    if !is_dir {
        // the new one was installed already, the old copy is stale
        return fs::remove_file(old);
    }
    // both exist: keep what the new dir has, take the rest from the old one
    for entry in fs::read_dir(old)? {
        let entry = entry?;
        let dest = Path::new(new).join(entry.file_name());
        if !dest.exists() {
            fs::rename(entry.path(), &dest)?;
        }
    }
    fs::remove_dir_all(old)?;
    info!("legacy: merged {old} into {new}");
    Ok(())
}

fn rename_legacy_files(working_dir: &str) {
    let Ok(entries) = fs::read_dir(working_dir) else {
        return;
    };
    let rc_name = Path::new(defs::KSURC_PATH)
        .file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let new_name = if name == LEGACY_RC_NAME {
            rc_name.clone()
        } else if let Some(rest) = name.strip_prefix(LEGACY_BACKUP_PREFIX) {
            format!("{}{rest}", defs::KSU_BACKUP_FILE_PREFIX)
        } else {
            continue;
        };
        let dest = Path::new(working_dir).join(&new_name);
        if dest.exists() {
            continue;
        }
        match fs::rename(entry.path(), &dest) {
            Ok(()) => info!("legacy: renamed {name} -> {new_name}"),
            Err(e) => warn!("legacy: rename {name}: {e}"),
        }
    }
}
