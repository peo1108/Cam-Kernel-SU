//! Flash an AnyKernel3 zip from a running system, the way kernel flasher
//! apps do: back up the target boot partition, then run the zip's
//! `update-binary` in boot mode with `OUTFD` pointing at stdout.
//!
//! With `inactive`, AnyKernel3 flashes the other slot (`SLOT_SELECT=inactive`)
//! and the device is switched to it, for installing right after an OTA.
//!
//! Backups live in `/data/adb/ksu/ak3_backup/`: `boot<slot>.img` is the kernel
//! that was there before the last flash, `boot<slot>-original.img` the one from
//! before the very first flash, which is never overwritten.

use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Seek, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{Context, Result, bail, ensure};

use crate::{assets, boot_patch, defs};

const UPDATE_BINARY: &str = "META-INF/com/google/android/update-binary";
const AK3_SCRIPT: &str = "anykernel.sh";
const ORIGINAL_SUFFIX: &str = "-original";

fn backup_dir() -> PathBuf {
    Path::new(defs::WORKING_DIR).join("ak3_backup")
}

fn boot_partition(slot_suffix: &str) -> Result<PathBuf> {
    let partition = PathBuf::from(format!("/dev/block/by-name/boot{slot_suffix}"));
    ensure!(
        partition.exists(),
        "boot partition {} not found",
        partition.display()
    );
    Ok(partition)
}

fn copy_synced(src: &Path, dst: &Path) -> Result<()> {
    let mut from = File::open(src).with_context(|| format!("open {}", src.display()))?;
    let mut to = File::create(dst).with_context(|| format!("create {}", dst.display()))?;
    io::copy(&mut from, &mut to)
        .with_context(|| format!("copy {} to {} failed", src.display(), dst.display()))?;
    to.sync_all()?;
    Ok(())
}

fn check_zip(zip_path: &Path) -> Result<()> {
    let file = File::open(zip_path).with_context(|| format!("open {}", zip_path.display()))?;
    let archive = zip::ZipArchive::new(file).context("not a valid zip file")?;
    let names: Vec<&str> = archive.file_names().collect();
    ensure!(
        names.contains(&UPDATE_BINARY),
        "{UPDATE_BINARY} is missing, this is not a flashable zip"
    );
    ensure!(
        names.contains(&AK3_SCRIPT),
        "{AK3_SCRIPT} is missing, this is not an AnyKernel3 zip"
    );
    Ok(())
}

fn extract_update_binary(zip_path: &Path, dest: &Path) -> Result<()> {
    let mut archive = zip::ZipArchive::new(File::open(zip_path)?)?;
    let mut entry = archive.by_name(UPDATE_BINARY)?;
    let mut out = File::create(dest).with_context(|| format!("create {}", dest.display()))?;
    io::copy(&mut entry, &mut out)?;
    Ok(())
}

/// Copies the boot partition about to be flashed so a bad kernel can be
/// undone with `ksud ak3-backup restore` or `fastboot flash boot<slot> <backup>`.
fn backup_boot(inactive: bool) -> Result<PathBuf> {
    let slot_suffix = boot_patch::get_slot_suffix(inactive);
    let partition = boot_partition(&slot_suffix)?;

    let dir = backup_dir();
    fs::create_dir_all(&dir)?;
    let backup = dir.join(format!("boot{slot_suffix}.img"));
    copy_synced(&partition, &backup)?;

    // keep the first kernel ever replaced, so the device can always go back to it
    let original = dir.join(format!("boot{slot_suffix}{ORIGINAL_SUFFIX}.img"));
    if !original.exists() {
        copy_synced(&backup, &original)?;
    }
    Ok(backup)
}

/// Prints the boot backups as JSON for the Manager.
pub fn list_backups() -> Result<()> {
    let mut backups = Vec::new();
    if let Ok(entries) = fs::read_dir(backup_dir()) {
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            let Some(stem) = name
                .strip_prefix("boot")
                .and_then(|n| n.strip_suffix(".img"))
            else {
                continue;
            };
            let (slot, original) = stem
                .strip_suffix(ORIGINAL_SUFFIX)
                .map_or((stem, false), |slot| (slot, true));
            let meta = entry.metadata()?;
            let modified = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_secs());
            backups.push(serde_json::json!({
                "path": path.to_string_lossy(),
                "slot": slot,
                "original": original,
                "size": meta.len(),
                "modified": modified,
            }));
        }
    }
    println!("{}", serde_json::Value::Array(backups));
    Ok(())
}

/// Writes a backup made by `flash` back to the boot partition of its slot.
pub fn restore_backup(file: &str) -> Result<()> {
    let path = fs::canonicalize(file).with_context(|| format!("realpath: {file} failed"))?;
    let dir = fs::canonicalize(backup_dir())?;
    ensure!(
        path.parent() == Some(dir.as_path()),
        "{} is not an AnyKernel3 boot backup",
        path.display()
    );
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .context("bad backup name")?;
    let stem = name
        .strip_prefix("boot")
        .and_then(|n| n.strip_suffix(".img"))
        .context("bad backup name")?;
    let slot_suffix = stem.strip_suffix(ORIGINAL_SUFFIX).unwrap_or(stem);
    let partition = boot_partition(slot_suffix)?;

    let backup_size = fs::metadata(&path)?.len();
    let partition_size = File::open(&partition)?.seek(io::SeekFrom::End(0))?;
    ensure!(
        backup_size <= partition_size,
        "backup ({backup_size} bytes) is larger than {} ({partition_size} bytes)",
        partition.display()
    );

    let mut stdout = io::stdout();
    writeln!(
        stdout,
        "- Restoring {} to {}",
        path.display(),
        partition.display()
    )?;
    let mut from = File::open(&path)?;
    let mut to = fs::OpenOptions::new().write(true).open(&partition)?;
    io::copy(&mut from, &mut to)?;
    to.sync_all()?;
    writeln!(stdout, "- Done, reboot to use the restored kernel")?;
    Ok(())
}

pub fn flash(zip: &str, no_backup: bool, inactive: bool) -> Result<()> {
    let zip_path = fs::canonicalize(zip).with_context(|| format!("realpath: {zip} failed"))?;
    check_zip(&zip_path)?;

    let mut stdout = io::stdout();
    if inactive {
        ensure!(
            !boot_patch::get_slot_suffix(false).is_empty(),
            "this device has no A/B slots"
        );
        writeln!(
            stdout,
            "- Target: inactive slot {}",
            boot_patch::get_slot_suffix(true)
        )?;
    }
    if no_backup {
        writeln!(stdout, "- Skipping boot backup")?;
    } else {
        writeln!(stdout, "- Backing up boot partition")?;
        let backup = backup_boot(inactive)?;
        writeln!(stdout, "- Boot backup: {}", backup.display())?;
    }

    let work_dir = Path::new(defs::WORKING_DIR).join("ak3");
    if work_dir.exists() {
        fs::remove_dir_all(&work_dir)?;
    }
    fs::create_dir_all(&work_dir)?;
    let update_binary = work_dir.join("update-binary");
    extract_update_binary(&zip_path, &update_binary)?;

    writeln!(stdout, "- Running AnyKernel3")?;
    stdout.flush()?;

    // update-binary <api> <outfd> <zip>; OUTFD=1 makes ui_print go to stdout
    let mut child = Command::new(assets::BUSYBOX_PATH)
        .arg("sh")
        .arg(&update_binary)
        .arg("3")
        .arg("1")
        .arg(&zip_path)
        .current_dir(&work_dir)
        .env("ASH_STANDALONE", "1")
        .env("OUTFD", "1")
        .env("BOOTMODE", "true")
        .env("KSU", "true")
        .env("SLOT_SELECT", if inactive { "inactive" } else { "active" })
        .stdout(Stdio::piped())
        .spawn()
        .context("failed to start update-binary")?;

    if let Some(out) = child.stdout.take() {
        for line in BufReader::new(out).lines() {
            let line = line?;
            let line = line
                .strip_prefix("ui_print")
                .map_or(line.as_str(), str::trim_start);
            // recovery protocol commands that only make sense to a recovery UI
            if line.starts_with("progress") || line.starts_with("set_progress") {
                continue;
            }
            writeln!(stdout, "{line}")?;
        }
    }

    let status = child.wait()?;
    let _ = fs::remove_dir_all(&work_dir);
    if !status.success() {
        bail!("AnyKernel3 failed with {status}");
    }

    if inactive {
        writeln!(stdout, "- Switching to the inactive slot")?;
        boot_patch::post_ota()?;
    }

    writeln!(stdout, "- Done, reboot to use the new kernel")?;
    Ok(())
}
