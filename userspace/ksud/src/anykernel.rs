//! Flash an AnyKernel3 zip from a running system, the way kernel flasher
//! apps do: back up the live boot partition, then run the zip's
//! `update-binary` in boot mode with `OUTFD` pointing at stdout.

use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{Context, Result, bail, ensure};

use crate::{assets, boot_patch, defs};

const UPDATE_BINARY: &str = "META-INF/com/google/android/update-binary";
const AK3_SCRIPT: &str = "anykernel.sh";

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

/// Copies the live boot partition so a bad kernel can be undone with
/// `fastboot flash boot <backup>`.
fn backup_boot() -> Result<PathBuf> {
    let slot_suffix = boot_patch::get_slot_suffix(false);
    let partition = PathBuf::from(format!("/dev/block/by-name/boot{slot_suffix}"));
    ensure!(
        partition.exists(),
        "boot partition {} not found",
        partition.display()
    );

    let backup_dir = Path::new(defs::WORKING_DIR).join("ak3_backup");
    fs::create_dir_all(&backup_dir)?;
    let backup = backup_dir.join(format!("boot{slot_suffix}.img"));

    let mut src = File::open(&partition)?;
    let mut dst = File::create(&backup)?;
    io::copy(&mut src, &mut dst)
        .with_context(|| format!("backup {} failed", partition.display()))?;
    dst.sync_all()?;
    Ok(backup)
}

pub fn flash(zip: &str, no_backup: bool) -> Result<()> {
    let zip_path = fs::canonicalize(zip).with_context(|| format!("realpath: {zip} failed"))?;
    check_zip(&zip_path)?;

    let mut stdout = io::stdout();
    if no_backup {
        writeln!(stdout, "- Skipping boot backup")?;
    } else {
        writeln!(stdout, "- Backing up current boot partition")?;
        let backup = backup_boot()?;
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

    writeln!(stdout, "- Done, reboot to use the new kernel")?;
    Ok(())
}
